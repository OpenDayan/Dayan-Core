import json

module_map = {}
with open('UnitTestConfig.json', 'r') as f:
    module_map = json.load(f)

# Generate One Module Makefile
def generateOneModuleMakefile(moduleName: str, modulePackage: str, AdditionDeps: dict):
    str_builder = ''
    for dep in AdditionDeps:
        str_builder +='build/V{Name}/V{Name}: {depName}\n'.format(Name=moduleName, depName=dep)
        str_builder +='{depName}: {depDep}\n	{depCmd}\n'.format(depName=dep, depDep = AdditionDeps[dep]['deps'], depCmd = AdditionDeps[dep]['cmd'])
    str_builder += '''
build/V{Name}/V{Name}: generated/{Name}/filelist.f build/V{Name}
	verilator --output-split 3000 --compiler clang -j 0 --assert --trace --trace-structs --trace-fst -CFLAGS -Wno-unknown-warning-option -CFLAGS -DVL_USER_FATAL -CFLAGS -DVL_USER_STOP -CFLAGS -Wno-narrowing --top {Name} -Mdir build/V{Name} -y generated/{Name}/ --cc -f generated/{Name}/filelist.f --exe csrc/test/{Name}Test.cpp
	make CXX=clang++-15 CC=clang-15 -C build/V{Name} -f V{Name}.mk V{Name}

.PHONY: generated/{Name}/filelist.f
generated/{Name}/filelist.f:
	./mill -i RVTNG-Chisel.runMain {Package}.{Name}

build/V{Name}:
	mkdir -p build/V{Name}

wave/V{Name}.fst: build/V{Name}/V{Name} wave
	build/V{Name}/V{Name} --trace-fst --trace-structs

.PHONY: surver_{Name}
surver_{Name}: wave/V{Name}.fst
	surver wave/V{Name}.fst --port 4000 --token 00000000
'''.format(Name=moduleName, Package=modulePackage)
    return str_builder

str_builder = 'all:'
for name in module_map:
    str_builder += ' wave/V{Name}.fst'.format(Name = name)

str_builder += '\n'
for name in module_map:
    deps = module_map[name].get('AdditionDeps')
    if deps == None:
        deps = {}
    str_builder += generateOneModuleMakefile(name, module_map[name]['Package'], deps)

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
unsigned int stopFlag = 0;

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
    while (sim_time < MAX_SIM_TIME)
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
        if(stopFlag != 0) {{
            if((--stopFlag) == 1) break;
        }}
    }}
    if(!stopFlag) {{
        printf("\\033[42;32mVerilator: {Name} Test PASS!\\033[0m\\n");
    }} else {{
        printf("\\033[41;32mVerilator: {Name} Test FAILED!\\033[0m\\n");
    }}
    m_trace->close();
    delete dut;
    exit(EXIT_SUCCESS);
}}

void vl_fatal(const char* filename, int line, const char* func, const char* msg) {{
    std::cerr << "Fatal error occurred at " << filename << ":" << line << " (function: " << func << ") - " << msg << std::endl;
    stopFlag = 5;
}}

void vl_stop(const char* filename, int line, const char* func) {{
    std::cerr << "stop occurred at " << filename << ":" << line << " (function: " << func << ")" << std::endl;
    stopFlag = 5;
}}
'''.format(Name=moduleName)

for name in module_map:
    with open('generated/testbenchTemplate/{Name}Test.cpp'.format(Name = name), 'w') as f:
        f.write(generateOneTestbenchTemplate(name))
