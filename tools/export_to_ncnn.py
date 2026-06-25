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
    """Patch the pnnx-generated Python script for dynamic input shapes."""
    lines = path.read_text().splitlines(keepends=True)
    out = []
    for line in lines:
        # Dynamic Area Attention reshape
        if "v_96 = v_95.view(1, 2, 128, 1024)" in line:
            line = line.replace("1024", "-1")
        if "v_106 = v_105.view(1, 128, 32, 32)" in line:
            line = "        v_106 = v_105.view(1, 128, v_95.size(2), v_95.size(3))\n"
        if "v_107 = v_99.reshape(1, 128, 32, 32)" in line:
            line = "        v_107 = v_99.reshape(1, 128, v_95.size(2), v_95.size(3))\n"

        # Dynamic reshape for detection head
        if ".view(1, " in line and any(
            x in line for x in ["6400", "1600", "400", "16384", "4096", "1024"]
        ):
            line = (
                line.replace("6400", "-1")
                .replace("1600", "-1")
                .replace("400", "-1")
                .replace("16384", "-1")
                .replace("4096", "-1")
                .replace("1024", "-1")
            )
            line = line.rstrip() + ".transpose(1, 2)\n"

        # Fix cat axis after transpose
        if "torch.cat" in line and "dim=2" in line:
            line = line.replace("dim=2", "dim=1")

        # Return raw detections from Model.forward() (8-space indent = class method body)
        if line.startswith("        return") and "v_" in line:
            line = "        return v_238\n"

        out.append(line)

    path.write_text("".join(out))
    print(f"  Patched {path.name}")


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

    print(f"\n=== Step 1: Export {weights.name} → TorchScript ===")
    run(f"yolo export model={weights} format=torchscript", cwd=work_dir)
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
