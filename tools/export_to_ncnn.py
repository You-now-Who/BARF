#!/usr/bin/env python3
"""
Convert a YOLO11 best.pt to ncnn .param/.bin files ready to drop into the Android assets.

Usage:
  python export_to_ncnn.py runs/detect/balls/weights/best.pt
  python export_to_ncnn.py runs/detect/balls/weights/best.pt --name yolo11n --assets ../android/src/main/assets
"""

import argparse
import importlib
import os
import shutil
import subprocess
import sys
from pathlib import Path


def parse_args():
    p = argparse.ArgumentParser()
    p.add_argument("weights", help="Path to best.pt")
    p.add_argument(
        "--name",
        default=None,
        help="Output basename (default: stem of weights file, e.g. 'yolo11n_balls')",
    )
    p.add_argument(
        "--assets",
        default=None,
        help="Android assets dir to copy final files into (optional)",
    )
    p.add_argument(
        "--out-dir",
        default=None,
        help="Directory to write intermediate and final files (default: same dir as weights)",
    )
    return p.parse_args()


def run(cmd, cwd=None):
    print(f"  $ {cmd}")
    result = subprocess.run(cmd, shell=True, cwd=cwd)
    if result.returncode != 0:
        sys.exit(f"Command failed (exit {result.returncode}): {cmd}")


def patch_pnnx_script(path: Path):
    """
    Patch the pnnx-generated Python script for dynamic input shapes.

    Target output format (per the ncnn yolo11_det.cpp contract):
        out0 = [N_grids, 64 + nc]   — each row is one grid cell with
                                       64 raw DFL box-reg values followed
                                       by nc raw class logits (pre-sigmoid).
        N_grids = sum of (H/s * W/s) for strides [8, 16, 32]
                  e.g. 8400 at 640px, 2100 at 320px.
    """
    import re

    lines = path.read_text().splitlines(keepends=True)

    # ── Pre-scan: find key variable names ──────────────────────────────────
    # 1. Attention spatial-reference tensor: the second output of the 128,128
    #    channel split that feeds the C2PSA block.  Its shape is [1,128,H,W]
    #    and we need it to reconstruct H/W for dynamic attention reshapes.
    spatial_ref = "v_93"  # sensible default for yolo11n at 640 px export
    for line in lines:
        m = re.match(
            r"\s+(v_\w+),\s*(v_\w+)\s*=\s*torch\.split\("
            r".*split_size_or_sections=\(128,\s*128\)",
            line,
        )
        if m:
            spatial_ref = m.group(2)
            break

    # 2. Detection-head cat variables: the two torch.cat calls that use
    #    dim=-1 are always (in this architecture) the DFL box-reg cat and
    #    the class-score cat respectively.
    dfl_cat_var = None
    cls_cat_var = None
    for line in lines:
        if "torch.cat" in line and "dim=-1" in line:
            m = re.match(r"\s+(v_\w+)\s*=\s*torch\.cat\(", line)
            if m:
                if dfl_cat_var is None:
                    dfl_cat_var = m.group(1)
                else:
                    cls_cat_var = m.group(1)

    # ── Line-by-line patching ───────────────────────────────────────────────
    out = []
    skip_postprocess = False   # True once we hit the anchor-decoding block

    for line in lines:

        # Rule A — C2PSA attention QKV reshape: (1, 2, 128, N) → (1, 2, 128, -1)
        # Handles both .view() (original COCO model) and .reshape() (fine-tuned).
        line = re.sub(
            r"(\.(?:view|reshape)\(1,\s*2,\s*128,\s*)\d+(\))",
            r"\g<1>-1\2",
            line,
        )

        # Rule B — C2PSA attention output reshape: (1, 128, H, W) → dynamic via spatial_ref.
        # Matches any 8-space-indented "v_X = v_Y.reshape(1, 128, <int>, <int>)".
        m = re.match(
            r"(        )(v_\w+)(\s*=\s*)(v_\w+)"
            r"\.(?:view|reshape)\(1,\s*128,\s*\d+,\s*\d+\)(.*)",
            line,
        )
        if m:
            line = (
                f"{m.group(1)}{m.group(2)}{m.group(3)}{m.group(4)}"
                f".reshape(1, 128, {spatial_ref}.size(2), {spatial_ref}.size(3))"
                f"{m.group(5)}\n"
            )

        # Rule C — DFL box-reg per-stride reshape: (1, 64, N) → (1, 64, -1).
        # These are the three reshape calls that flatten each stride's spatial map.
        line = re.sub(
            r"(\.(?:view|reshape)\(1,\s*64,\s*)\d+(\))",
            r"\g<1>-1\2",
            line,
        )

        # Rule D — Class-score per-stride reshape: (1, nc, N) → (1, nc, -1)
        # where nc is small (model's num_classes, not 64 or 128) and N > 100.
        m_d = re.search(r"\.(?:view|reshape)\(1,\s*(\d+),\s*(\d+)\)", line)
        if m_d:
            nc, n_s = int(m_d.group(1)), int(m_d.group(2))
            if nc not in (64, 128) and 1 <= nc <= 100 and n_s > 100:
                line = re.sub(
                    r"(\.(?:view|reshape)\(1,\s*" + str(nc) + r",\s*)\d+(\))",
                    r"\g<1>-1\2",
                    line,
                )

        # Skip post-processing (anchor decoding with fixed 640 px anchor grid).
        # Triggered by the first reshape of the concatenated DFL tensor into
        # (1, 4, 16, N) for the DFL soft-max decoding step.
        if dfl_cat_var and cls_cat_var:
            if re.search(
                rf"=\s*{re.escape(dfl_cat_var)}\.(?:view|reshape)\(1,\s*4,\s*16,",
                line,
            ):
                skip_postprocess = True

        if skip_postprocess:
            # The original "return" statement in Model.forward() is where we
            # inject the correct combined output tensor.
            if line.startswith("        return") and "v_" in line:
                skip_postprocess = False
                # Output: [N_grids, 64+nc]  (matches ncnn yolo11_det.cpp expectations)
                # v_207 = [1, 64, N_grids], v_238 = [1, nc, N_grids]
                # → transpose each to [1, N_grids, C] → cat on last dim → squeeze batch
                line = (
                    f"        return torch.cat(\n"
                    f"            ({dfl_cat_var}.transpose(1, 2), {cls_cat_var}.transpose(1, 2)),\n"
                    f"            dim=2,\n"
                    f"        ).squeeze(0)\n"
                )
                out.append(line)
            # Drop all other post-processing lines
            continue

        out.append(line)

    path.write_text("".join(out))
    print(
        f"  Patched {path.name}  "
        f"[spatial_ref={spatial_ref}, dfl_cat={dfl_cat_var}, cls_cat={cls_cat_var}]"
    )


def main():
    args = parse_args()
    weights = Path(args.weights).resolve()
    if not weights.exists():
        sys.exit(f"Weights file not found: {weights}")

    name = args.name or weights.stem  # e.g. "best" or "yolo11n_balls"
    out_dir = Path(args.out_dir).resolve() if args.out_dir else weights.parent.parent.parent
    # Default: run from tools/ next to the notebook
    work_dir = out_dir
    work_dir.mkdir(parents=True, exist_ok=True)

    print(f"\n=== Step 1: Export {weights.name} → TorchScript (imgsz=640) ===")
    # Always export at 640 regardless of training imgsz — the phone inference
    # uses 320 or 640 at runtime; dynamic-shape NCNN requires a 640-traced base.
    run(f"yolo export model={weights} format=torchscript imgsz=640", cwd=work_dir)
    ts_src = weights.with_suffix(".torchscript")
    ts_dst = work_dir / f"{name}.torchscript"
    shutil.copy2(ts_src, ts_dst)
    print(f"  Copied {ts_src.name} → {ts_dst.name}")

    print(f"\n=== Step 2: Initial pnnx conversion ===")
    run(f"pnnx {ts_dst.name}", cwd=work_dir)

    pnnx_py = work_dir / f"{name}_pnnx.py"
    if not pnnx_py.exists():
        sys.exit(f"pnnx did not produce {pnnx_py} — check pnnx output above")

    print(f"\n=== Step 3: Patch pnnx script for dynamic shapes ===")
    patch_pnnx_script(pnnx_py)

    print(f"\n=== Step 4: Re-export patched script → TorchScript ===")
    sys.path.insert(0, str(work_dir))
    sys.modules.pop(f"{name}_pnnx", None)
    try:
        mod = importlib.import_module(f"{name}_pnnx")
        os.chdir(work_dir)  # pnnx module writes relative to cwd
        mod.export_torchscript()
        print("  Re-exported patched script")
    except Exception as e:
        print(f"  Re-export via import failed: {e!r} — falling back to pnnx direct")

    print(f"\n=== Step 5: Final pnnx → ncnn (dual shape: 640 + 320) ===")
    patched_pt = work_dir / f"{name}_pnnx.py.pt"
    if patched_pt.exists():
        run(
            f"pnnx {patched_pt.name} inputshape=[1,3,640,640] inputshape2=[1,3,320,320]",
            cwd=work_dir,
        )
        param_src = work_dir / f"{name}_pnnx.py.ncnn.param"
        bin_src   = work_dir / f"{name}_pnnx.py.ncnn.bin"
    else:
        # Fallback: use static-shape ncnn files from step 2
        print("  Patched .pt not found — using static-shape ncnn files from step 2")
        param_src = work_dir / f"{name}.ncnn.param"
        bin_src   = work_dir / f"{name}.ncnn.bin"

    print(f"\n=== Step 6: Rename to final asset names ===")
    # The Android app loads "yolo11n.ncnn.param" / "yolo11n.ncnn.bin"
    # Figure out the variant suffix (n / s / m / l / x) from the weights model type if possible
    final_param = work_dir / "yolo11n.ncnn.param"
    final_bin   = work_dir / "yolo11n.ncnn.bin"

    for src, dst in [(param_src, final_param), (bin_src, final_bin)]:
        if src.exists():
            shutil.copy2(src, dst)
            print(f"  {src.name} → {dst.name}")
        else:
            print(f"  WARNING: {src.name} not found — check pnnx output above")

    if args.assets:
        assets = Path(args.assets).resolve()
        print(f"\n=== Step 7: Deploy to Android assets ({assets}) ===")
        if not assets.exists():
            print(f"  WARNING: assets dir {assets} does not exist — skipping deploy")
        else:
            for f in [final_param, final_bin]:
                if f.exists():
                    shutil.copy2(f, assets / f.name)
                    print(f"  Deployed {f.name} → {assets}")

    print("\n=== Done ===")
    print(f"  Output files in: {work_dir}")
    for f in [final_param, final_bin]:
        status = "OK" if f.exists() else "MISSING"
        print(f"  [{status}] {f.name}")

    print("""
Next steps:
  1. Copy yolo11n.ncnn.param + yolo11n.ncnn.bin to android/src/main/assets/
     (or pass --assets ../android/src/main/assets to do it automatically)

  2. Update class_names in android/src/main/jni/yolo11_det.cpp:
       static const char* class_names[] = { "ping_pong_ball", "metal_ball" };

  3. Rebuild the APK.
""")


if __name__ == "__main__":
    main()
