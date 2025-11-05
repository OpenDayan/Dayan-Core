#include <stdlib.h>
#include <iostream>
#include <verilated.h>
#include "verilated_fst_c.h"
#include "VVecTinyTestTop.h"
#include <vector>
#include <queue>
#define MAX_SIM_TIME 1000
VerilatedContext *contextp;
VVecTinyTestTop *dut;
unsigned long long sim_time = 0;
unsigned int stopFlag = 0;

// Tiny Configuration, 向量寄存器车道宽度为 32， 运算器个数为 16， SFU 为 4 个。
// URAM == (32 * 32) / 2 / 64 == 8 Banks

#define LOG2BANK 3
#define URAMSIZE ((1 << LOG2BANK) * 32 * 1024ull)
long int uram[URAMSIZE / 8]; // 1024KBytes URAM
static int* uramBase = (int*)&uram;

extern "C" long long UltraRamRead(int id, int raddr)
{
    long long rtValue = uram[id | (raddr << LOG2BANK)];
    return rtValue;
}

extern "C" long long UltraRamWrite(int id, int waddr, long int wdata)
{
    uram[id | (waddr << LOG2BANK)] = wdata;
    return 0;
}

typedef struct {
 	uint32_t inst;
    uint64_t rd;
    uint64_t rs1;
    uint64_t rs2;
} rvt_pack_t;

typedef struct {
 	uint8_t rd;
 	uint64_t data;
} rvt_commit_t;

extern "C" long fetch_tensor_inst(rvt_pack_t *inst);
extern "C" long commit_tensor_inst(rvt_commit_t *info);
extern "C" int nemu_main(int argc, char *argv[]);

static inline void mainLoop()
{

}

int main(int argc, char **argv)
{
    // INIT NEMU
    const char* paramsArray[] = {"nemu_thread", "-b"};
    std::thread nemu_thread(nemu_main, 2, (char**)paramsArray);

    contextp = new VerilatedContext;
    contextp->commandArgs(argc, argv);
    dut = new VVecTinyTestTop;
    Verilated::traceEverOn(true);
    VerilatedFstC *m_trace = new VerilatedFstC;
    dut->trace(m_trace, 99);
    m_trace->open("wave/VVecTinyTestTop.fst");
    dut->clock = 0;
    dut->reset = 1;
    dut->eval();
    m_trace->dump(contextp->time());
    contextp->timeInc(1);
    for (int i = 0; i < 20; i++)
    {
        dut->clock ^= 1;
        dut->eval();
        m_trace->dump(contextp->time());
        contextp->timeInc(1);
    }
    dut->reset = 0;
    while (sim_time < MAX_SIM_TIME)
    {
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
        if(stopFlag != 0) {
            if((--stopFlag) == 1) break;
        }
    }
    if(!stopFlag) {
        printf("\033[42;32mVerilator: VecTinyTestTop Test PASS!\033[0m\n");
    } else {
        printf("\033[41;32mVerilator: VecTinyTestTop Test FAILED!\033[0m\n");
    }
    m_trace->close();
    delete dut;
    exit(EXIT_SUCCESS);
}

void vl_fatal(const char* filename, int line, const char* func, const char* msg) {
    std::cerr << "Fatal error occurred at " << filename << ":" << line << " (function: " << func << ") - " << msg << std::endl;
    stopFlag = 5;
}

void vl_stop(const char* filename, int line, const char* func) {
    std::cerr << "stop occurred at " << filename << ":" << line << " (function: " << func << ")" << std::endl;
    stopFlag = 5;
}
