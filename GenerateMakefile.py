import json

with open('UnitTestConfig.json', 'r') as f:
    module_map = json.load(f)

# Generate One Module Makefile
def generateOneModuleMakefile(moduleName: str, modulePackage: str):
    return '''
build/V{Name}/V{Name}: generated/{Name}/filelist.f build/V{Name}
	verilator --assert --trace --trace-fst -CFLAGS -Wno-narrowing --top {Name} -Mdir build/V{Name} -y generated/{Name}/ --cc -f generated/{Name}/filelist.f --exe csrc/test/{Name}Test.cpp
	make -C build/V{Name} -f V{Name}.mk V{Name}

.PHONY: generated/{Name}/filelist.f
generated/{Name}/filelist.f:
	./mill GPU-Chisel.runMain {Package}.{Name}

build/V{Name}:
	mkdir -p build/V{Name}

wave/V{Name}.fst: build/V{Name}/V{Name} wave
	build/V{Name}/V{Name} --trace-fst

.PHONY: surver_{Name}
surver_{Name}: wave/V{Name}.fst
	surver wave/V{Name}.fst --port 4000 --token 00000000
'''.format(Name=moduleName, Package=modulePackage)

str_builder = 'all:'
for name in module_map:
    str_builder += ' wave/V{Name}.fst'.format(Name = name)

str_builder += '\n'
for name in module_map:
    str_builder += generateOneModuleMakefile(name, module_map[name]['Package'])

with open('UnitTest.mk', 'w') as f:
    f.write(str_builder)

# Generate Testbench Template
def generateOneTestbenchTemplate(moduleName: str):
    return '''#include <stdlib.h>
#include <iostream>
#include <verilated.h>
#include "verilated_fst_c.h"
#include "V{Name}.h"
#include <vector>
#include <queue>
#define MAX_SIM_TIME 1000
VerilatedContext *contextp;
V{Name} *dut;
unsigned long long sim_time = 0;
bool stopFlag = false;

static inline void mainLoop()
{{
}}
int main(int argc, char **argv)
{{
    contextp = new VerilatedContext;
    contextp->commandArgs(argc, argv);
    dut = new V{Name};
    Verilated::traceEverOn(true);
    VerilatedFstC *m_trace = new VerilatedFstC;
    dut->trace(m_trace, 99);
    m_trace->open(\"wave/V{Name}.fst\");
    dut->clock = 0;
    dut->reset = 1;
    dut->eval();
    m_trace->dump(contextp->time());
    contextp->timeInc(1);
    for (int i = 0; i < 20; i++)
    {{
        dut->clock ^= 1;
        dut->eval();
        m_trace->dump(contextp->time());
        contextp->timeInc(1);
    }}
    dut->reset = 0;
    while (sim_time < MAX_SIM_TIME && !stopFlag)
    {{
        dut->clock ^= 1;
        dut->eval();
        mainLoop();
        m_trace->dump(contextp->time());
        contextp->timeInc(1);
        dut->clock ^= 1;
        dut->eval();
        m_trace->dump(contextp->time());
        contextp->timeInc(1);
        sim_time++;
    }}
    m_trace->close();
    delete dut;
    exit(EXIT_SUCCESS);
}}
'''.format(Name=moduleName)

for name in module_map:
    with open('generated/testbenchTemplate/{Name}Test.cpp'.format(Name = name), 'w') as f:
        f.write(generateOneTestbenchTemplate(name))
