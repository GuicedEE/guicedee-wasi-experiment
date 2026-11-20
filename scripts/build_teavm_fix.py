"""Build isolated TeaVM coroutine fixes without replacing published artifacts.

Upstream reference: konsoletyper/teavm at 5e8f489fa49676ca166bd98625ac8a65dc44c883,
core/src/main/java/org/teavm/backend/lowlevel/transform/CoroutineTransformation.java.
Original sources retain their Apache-2.0 header. Published jars are never overwritten.
Revision 1 contains the upstream entry-block liveness fix. Revision 2 additionally
restores SSA before irreducible-graph cloning; that repair is local to this experiment.
Revision 3 repairs annotated generic C initializers and primitive array inference.
"""
import hashlib
import argparse
import json
import os
import subprocess
import tempfile
import difflib
from pathlib import Path
import urllib.request
import zipfile
from wasmtime_tool import ROOT

PATH = "org/teavm/backend/lowlevel/transform/CoroutineTransformation"
SOURCE_SHA = "687cabf23e0d17a15be30fbc070809c2931714340aca224120fe31579f95519b"
BINARY_SHA = "c94539912c1b951fa741aecfcb6b4acad75c2e2360dc8859b1edd0c44175608a"


def checked(path, expected):
    actual = hashlib.sha256(path.read_bytes()).hexdigest()
    if actual != expected:
        raise RuntimeError(f"Unexpected TeaVM input {path.name}: {actual}")


def build(revision=2):
    version = f"0.16.0-guicedee-wasi-{revision}"
    repo = ROOT / ".cache/m2/org/teavm"
    original = repo / "teavm-core/0.16.0/teavm-core-0.16.0.jar"
    checked(original, BINARY_SHA)
    sources = ROOT / ".cache/sources/teavm-core-0.16.0-sources.jar"
    sources.parent.mkdir(parents=True, exist_ok=True)
    if not sources.exists():
        urllib.request.urlretrieve(
            "https://repo.maven.apache.org/maven2/org/teavm/teavm-core/0.16.0/" + sources.name, sources)
    checked(sources, SOURCE_SHA)
    output = ROOT / "target/compiler-fix"
    source = output / "src" / (PATH + ".java")
    source.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(sources) as archive:
        code = archive.read(PATH + ".java").decode()
    replacements = [
        ("    private SwitchInstruction resumeSwitch;", "    private SwitchInstruction resumeSwitch;\n    private int entryBlockReplacement;"),
        ("        BasicBlock continueBlock = splitter.split(firstBlock, null);", "        BasicBlock continueBlock = splitter.split(firstBlock, null);\n        entryBlockReplacement = continueBlock.getIndex();"),
        ("        BitSet live = livenessAnalysis.liveOut(block.getIndex());", "        int livenessIndex = block.getIndex() == entryBlockReplacement ? 0 : block.getIndex();\n        BitSet live = livenessAnalysis.liveOut(livenessIndex);"),
    ]
    if revision >= 2:
        replacements.append(("        SplittingBackend splittingBackend = new SplittingBackend();",
            "        // Restore SSA before cloning blocks to make the resumed CFG reducible.\n"
            "        new PhiUpdater().updatePhis(program, parameterCount + 1);\n"
            "        SplittingBackend splittingBackend = new SplittingBackend();"))
    fixes = ["upstream: entry-block coroutine liveness"]
    if revision >= 2:
        fixes.append("local: restore SSA before irreducible-graph cloning")
    for old, new in replacements:
        if code.count(old) != 1:
            raise RuntimeError("Unexpected TeaVM source layout")
        code = code.replace(old, new)
    # Published TeaVM binaries relocate HPPC; compile against their actual package.
    code = code.replace("import com.carrotsearch.hppc.", "import org.teavm.hppc.")
    source.write_text(code, encoding="utf-8")
    source_files = [source]
    expected_classes = {PATH + ".class", PATH + "$1.class", PATH + "$SplittingBackend.class"}
    if revision >= 3:
        repairs = {
            "org/teavm/backend/c/generate/ClassReflectionGenerator": (
                '            if (paramGenericType != null) {\n                writer.print(" .genericType = ");',
                '            if (paramGenericType != null) {\n                if (!annots.isEmpty()) writer.print(",");\n                writer.print(" .genericType = ");'),
            "org/teavm/model/util/TypeInferer": (
                '            arrayElemBuilder.addEdge(array.getIndex(), receiver.getIndex());',
                '''            // Coroutine restoration returns Object. Do not let an erased
            // array reference turn a primitive array load into an object GC root.
            if (type == ArrayElementType.OBJECT) {
                arrayElemBuilder.addEdge(array.getIndex(), receiver.getIndex());
            } else {
                types[receiver.getIndex()] = new InferenceType(InferenceKind.valueOf(type.name()), 0);
            }'''),
        }
        with zipfile.ZipFile(sources) as archive:
            for name, (before, after) in repairs.items():
                original_source = archive.read(name + ".java").decode()
                if original_source.count(before) != 1: raise RuntimeError("Unexpected compiler source: " + name)
                adapted = original_source.replace(before, after).replace("import com.carrotsearch.hppc.", "import org.teavm.hppc.")
                path = output / "src" / (name + ".java")
                path.parent.mkdir(parents=True, exist_ok=True); path.write_text(adapted, encoding="utf-8")
                source_files.append(path)
                (output / (path.stem + ".patch")).write_text(''.join(difflib.unified_diff(
                    original_source.splitlines(True), adapted.splitlines(True), fromfile=name + ".java", tofile=name + ".java")), encoding="utf-8")
                expected_classes.add(name + ".class")
        expected_classes.update({"org/teavm/model/util/TypeInferer" + suffix + ".class"
            for suffix in ("$1", "$2", "$InferenceKind", "$InferenceType")})
        expected_classes.add("org/teavm/backend/c/generate/ClassReflectionGenerator$1.class")
        fixes += ["local: separate annotated generic parameter C initializers",
                  "local: infer primitive array loads from their opcode after coroutine restoration"]
    classes = Path(tempfile.mkdtemp(prefix="classes-", dir=output))
    dependencies = [original, repo / "teavm-interop/0.16.0/teavm-interop-0.16.0.jar",
                    repo / "teavm-relocated-libs-hppc/0.16.0/teavm-relocated-libs-hppc-0.16.0.jar"]
    subprocess.run(["javac", "--release", "21", "-cp", os.pathsep.join(map(str, dependencies)),
                    "-d", str(classes), *map(str, source_files)], check=True)
    jar = output / f"teavm-core-{version}.jar"
    patches = {p.relative_to(classes).as_posix(): p.read_bytes() for p in classes.rglob("*.class")}
    if set(patches) != expected_classes:
        raise RuntimeError("Unexpected compiler replacement classes: " + str(set(patches) ^ expected_classes))
    with zipfile.ZipFile(original) as src, zipfile.ZipFile(jar, "w", zipfile.ZIP_DEFLATED) as dst:
        for item in src.infolist():
            dst.writestr(item, patches.get(item.filename, src.read(item.filename)))
        dst.writestr(zipfile.ZipInfo("META-INF/guicedee-wasi-backport.txt", (2026, 11, 15, 0, 0, 0)),
                     ("TeaVM 0.16.0 experiment only.\n" + "\n".join(fixes) + "\n") if revision >= 2
                     else "TeaVM 0.16.0 + entry-block coroutine liveness fix; experiment only.\n")
    pom = (repo / "teavm-core/0.16.0/teavm-core-0.16.0.pom").read_text(encoding="utf-8")
    (output / "pom.xml").write_text(pom.replace("<version>0.16.0</version>", f"<version>{version}</version>", 1), encoding="utf-8")
    evidence = {"version": version, "original_jar_sha256": BINARY_SHA, "original_sources_sha256": SOURCE_SHA,
                "upstream_reference": "5e8f489fa49676ca166bd98625ac8a65dc44c883",
                "fixes": fixes,
                "patched_source_sha256": hashlib.sha256(source.read_bytes()).hexdigest(),
                "patched_sources_sha256": {str(p.relative_to(output / "src")): hashlib.sha256(p.read_bytes()).hexdigest() for p in source_files},
                "patched_jar_sha256": hashlib.sha256(jar.read_bytes()).hexdigest(),
                "replacement_classes": sorted(patches)}
    (output / "backport.json").write_text(json.dumps(evidence, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(evidence, indent=2))


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--revision", type=int, choices=[2, 3], default=2)
    build(parser.parse_args().revision)
