"""Prepare an importable local model ZIP from official sherpa archives; stdlib only."""
import argparse
import hashlib
import json
import tarfile
import zipfile
from pathlib import Path


def build(root, output):
    files = {}
    archives = [(root / "sensevoice.tar.bz2", {"model.int8.onnx": "asr/model.int8.onnx", "tokens.txt": "asr/tokens.txt", "LICENSE": "licenses/SenseVoice-LICENSE.txt"}),
                (root / "punctuation.tar.bz2", {"model.int8.onnx": "punct/model.int8.onnx", "README.md": "licenses/CT-Transformer-README.md"})]
    for path, selected in archives:
        with tarfile.open(path, "r:bz2") as archive:
            for member in archive.getmembers():
                # Read only exact first-level model payloads, never extract archive paths.
                parts = member.name.split("/")
                if member.isfile() and len(parts) == 2 and parts[-1] in selected:
                    files[selected[parts[-1]]] = archive.extractfile(member).read()
    files["vad/silero_vad.onnx"] = (root / "silero_vad.onnx").read_bytes()
    license_root = Path(__file__).resolve().parent.parent / "docs" / "licenses"
    for name in ("FunASR-MODEL-LICENSE.txt", "Silero-MIT.txt"):
        files["licenses/" + name] = (license_root / name).read_bytes()
    files["licenses/ATTRIBUTION.txt"] = (
        "SenseVoice Small: FunAudioLLM / Alibaba / FunASR.\n"
        "CT-Transformer: Alibaba / ModelScope / FunASR.\n"
        "ONNX INT8 conversion: k2-fsa/sherpa-onnx community.\n"
        "Silero VAD: Silero Team. See included licenses and manifest sources.\n"
    ).encode()
    required = {"asr/model.int8.onnx", "asr/tokens.txt", "punct/model.int8.onnx", "vad/silero_vad.onnx"}
    if not required <= files.keys():
        raise ValueError("Official archives did not contain all required files")
    manifest = {"schema": 1, "name": "SenseVoice Small INT8 · 2024-07-17", "engine": "sensevoice",
                "asr_source": "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.tar.bz2",
                "punctuation_source": "https://github.com/k2-fsa/sherpa-onnx/releases/download/punctuation-models/sherpa-onnx-punct-ct-transformer-zh-en-vocab272727-2024-04-12-int8.tar.bz2",
                "sha256": {name: hashlib.sha256(data).hexdigest() for name, data in files.items()}}
    output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=1) as archive:
        archive.writestr("manifest.json", json.dumps(manifest, ensure_ascii=False, indent=2))
        for name, data in files.items():
            archive.writestr(name, data)
    print(json.dumps({"zip": str(output), "bytes": output.stat().st_size, "files": manifest["sha256"]}, ensure_ascii=False))


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=Path(".tools/models"))
    parser.add_argument("--output", type=Path, default=Path("dist/sensevoice-offline-models.zip"))
    args = parser.parse_args()
    build(args.root, args.output)
