"""Adapt the pinned TeaVM C command entry to a sequential WASI HTTP reactor."""
from pathlib import Path
import sys


def replace_once(text, old, new):
    if text.count(old) != 1:
        raise RuntimeError(f"Unexpected TeaVM C output near {old!r}")
    return text.replace(old, new)


def prepare_reactor(directory):
    directory = Path(directory)
    generated = (directory / "main.c").read_text()
    reactor = replace_once(
        generated,
        "int main(int argc, char** argv) {\n",
        "int inbound_dispatch(int argc, char** argv) {\n"
        "    static bool initialized;\n"
        "    if (!initialized) {\n",
    )
    reactor = replace_once(
        reactor,
        "    meth_otr_Fiber_startMain(teavm_parseArguments(argc, argv));\n"
        "    meth_otr_EventQueue_process();\n"
        "    return 0;\n",
        "        initialized = true;\n"
        "    }\n"
        "    /* TeaVM stops the queue when the last non-daemon fiber exits. */\n"
        "    sfld_otr_EventQueue_finished = 0;\n"
        "    meth_otr_Fiber_startMain(teavm_parseArguments(argc, argv));\n"
        "    meth_otr_EventQueue_process();\n"
        "    return sfld_otr_EventQueue_size == 0 ? 0 : -1;\n",
    )
    (directory / "reactor_main.c").write_text(reactor)
    unit = directory / "wasi_all.c"
    unit.write_text(replace_once(unit.read_text(), '#include "main.c"', '#include "reactor_main.c"'))


if __name__ == "__main__":
    prepare_reactor(sys.argv[1])
