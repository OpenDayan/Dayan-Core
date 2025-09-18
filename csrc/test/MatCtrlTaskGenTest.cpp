#include <stdlib.h>
#include <iostream>
#include <verilated.h>
#include "verilated_fst_c.h"
#include "VMatCtrlTaskGen.h"
#include <vector>
#include <queue>
#define MAX_SIM_TIME 1000
VerilatedContext *contextp;
VMatCtrlTaskGen *dut;
unsigned long long sim_time = 0;
unsigned int stopFlag = 0;

#define DELAY_LD   20
#define DELAY_CALC_RD 128
#define DELAY_CALC_WR 6
#define DELAY_ST   20

static inline void mainLoop()
{
    static std::queue<int> calcRdDelaySim;
    static std::queue<int> calcWrDelaySim;
    static std::queue<int> ldDelaySim;
    static std::queue<int> stDelaySim;
    dut->ldComp_valid = 0;
    dut->crComp_valid = 0;
    dut->cwComp_valid = 0;
    dut->fComp_ready = 1;
    dut->stComp_valid = 0;
    dut->calcCmd_ready = 1;
    dut->ldCmd_ready = 1;
    dut->stCmd_ready = 1;
    // Task Input
    if(sim_time >= 10) {
        dut->task_valid = 1;
        dut->task_bits_bf16 = 1;
        dut->task_bits_aBaseAddr = 0;
        dut->task_bits_bBaseAddr = 0x1000;
        dut->task_bits_cBaseAddr = 0x2000;
        dut->task_bits_log2SubM = 2;
        dut->task_bits_log2SubN = 2;
        dut->task_bits_log2SubK = 2;
        dut->task_bits_persistentMode = 1; // A Persistence
        dut->task_bits_taskM = 32;
        dut->task_bits_taskN = 10;
        dut->task_bits_taskK = 8;
    }
    // Delay Simulate
    calcRdDelaySim.push(dut->calcCmd_valid);
    ldDelaySim.push(dut->ldCmd_valid && ((dut->ldCmd_bits_lastLoadAB && !dut->ldCmd_bits_waitLoadC) | (dut->ldCmd_bits_ctrl == 2)));
    stDelaySim.push(dut->stCmd_valid);
    if(dut->ldCmd_valid && dut->ldCmd_bits_ctrl == 0) {
        printf("Load A%d[%d:%d, %d:%d]\n", dut->ldCmd_bits_bank,
            dut->ldCmd_bits_baseDimMajor, dut->ldCmd_bits_baseDimMajor + dut->ldCmd_bits_pMajor,
            dut->ldCmd_bits_baseDimMinor, dut->ldCmd_bits_baseDimMinor + dut->ldCmd_bits_pMinor
    );
    }
    if(dut->ldCmd_valid && dut->ldCmd_bits_ctrl == 1) {
        printf("Load BT%d[%d:%d, %d:%d]\n", dut->ldCmd_bits_bank,
            dut->ldCmd_bits_baseDimMajor, dut->ldCmd_bits_baseDimMajor + dut->ldCmd_bits_pMajor,
            dut->ldCmd_bits_baseDimMinor, dut->ldCmd_bits_baseDimMinor + dut->ldCmd_bits_pMinor
    );
    }
    if(dut->ldCmd_valid && dut->ldCmd_bits_ctrl == 2) {
        printf("Load C%d[%d:%d, %d:%d]\n", dut->ldCmd_bits_bank,
            dut->ldCmd_bits_baseDimMajor, dut->ldCmd_bits_baseDimMajor + dut->ldCmd_bits_pMajor,
            dut->ldCmd_bits_baseDimMinor, dut->ldCmd_bits_baseDimMinor + dut->ldCmd_bits_pMinor
    );
    }
    if(dut->stCmd_valid) {
        printf("Store C%d[%d:%d, %d:%d]\n", dut->stCmd_bits_bank,
            dut->stCmd_bits_baseDimMajor, dut->stCmd_bits_baseDimMajor + dut->stCmd_bits_pMajor,
            dut->stCmd_bits_baseDimMinor, dut->stCmd_bits_baseDimMinor + dut->stCmd_bits_pMinor
        );
    }
    if(dut->calcCmd_valid) {
        int M = 16 * (1 + dut->calcCmd_bits_pM);
        int N = 8  * (1 + dut->calcCmd_bits_pN);
        int K = 32 * (1 + dut->calcCmd_bits_pK);
        printf("Calc C%d[%d,%d] += A%d[%d,%d] x BT%d[%d,%d] \n",
            dut->calcCmd_bits_cBank, M, N,
            dut->calcCmd_bits_aBank, M, K,
            dut->calcCmd_bits_bBank, N, K);
    }
    if(dut->fComp_valid) {
        printf("Finish One Round Computation.\n");
    }
    if(calcRdDelaySim.size() > DELAY_CALC_RD) {
        dut->crComp_valid = calcRdDelaySim.front();
        calcWrDelaySim.push(calcRdDelaySim.front());
        calcRdDelaySim.pop();
    }
    if(calcWrDelaySim.size() > DELAY_CALC_WR) {
        dut->cwComp_valid = calcWrDelaySim.front();
        if(dut->cwComp_valid) printf("Calc Completion.\n");
        calcWrDelaySim.pop();
    }
    if(ldDelaySim.size() > DELAY_LD) {
        dut->ldComp_valid = ldDelaySim.front();
        if(dut->ldComp_valid) printf("Load Completion.\n");
        ldDelaySim.pop();
    }
    if(stDelaySim.size() > DELAY_ST) {
        dut->stComp_valid = stDelaySim.front();
        if(dut->stComp_valid) printf("Store Completion.\n");
        stDelaySim.pop();
    }
}
int main(int argc, char **argv)
{
    contextp = new VerilatedContext;
    contextp->commandArgs(argc, argv);
    dut = new VMatCtrlTaskGen;
    Verilated::traceEverOn(true);
    VerilatedFstC *m_trace = new VerilatedFstC;
    dut->trace(m_trace, 99);
    m_trace->open("wave/VMatCtrlTaskGen.fst");
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
        printf("\033[42;32mVerilator: MatCtrlTaskGen Test PASS!\033[0m\n");
    } else {
        printf("\033[41;32mVerilator: MatCtrlTaskGen Test FAILED!\033[0m\n");
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
