#include <stdlib.h>
#include <iostream>
#include <verilated.h>
#include "verilated_fst_c.h"
#include "VAdv2R1WURAM.h"
#include <vector>
#include <queue>
#define MAX_SIM_TIME 1000
VerilatedContext *contextp;
VAdv2R1WURAM *dut;
unsigned long long sim_time = 0;
unsigned int stopFlag = 0;

extern "C" long long UltraRamRead(int id, int raddr)
{
    return id | (raddr << 2);
}

extern "C" long long UltraRamWrite(int id, int waddr, long long wdata)
{
    return 0;
}

#define REQ(Group, Port, Field) (dut->r##Group##req_i_##Port##_##Field)
#define RNDREQ(Group, Port) \
    do { \
        REQ(Group, Port, valid) = 1; \
        REQ(Group, Port, bits_address) = rand() & ((1 << 14) - 1); \
        REQ(Group, Port, bits_id)      = 0; \
    } while(0)

static inline void mainLoop()
{
    RNDREQ(0, 0_0);
    RNDREQ(0, 0_1);
    RNDREQ(0, 0_2);
    RNDREQ(0, 0_3);
    RNDREQ(0, 1_0);
    RNDREQ(0, 1_1);
    RNDREQ(0, 1_2);
    RNDREQ(0, 1_3);
    RNDREQ(1, 0_0);
    RNDREQ(1, 0_1);
    RNDREQ(1, 0_2);
    RNDREQ(1, 0_3);
    RNDREQ(1, 1_0);
    RNDREQ(1, 1_1);
    RNDREQ(1, 1_2);
    RNDREQ(1, 1_3);
    dut->r0resp_o_0_0_ready = 1;
    dut->r0resp_o_0_1_ready = 1;
    dut->r0resp_o_1_0_ready = 1;
    dut->r0resp_o_1_1_ready = 1;
    dut->r1resp_o_0_0_ready = 1;
    dut->r1resp_o_0_1_ready = 1;
    dut->r1resp_o_1_0_ready = 1;
    dut->r1resp_o_1_1_ready = 1;
}

int main(int argc, char **argv)
{
    contextp = new VerilatedContext;
    contextp->commandArgs(argc, argv);
    dut = new VAdv2R1WURAM;
    Verilated::traceEverOn(true);
    VerilatedFstC *m_trace = new VerilatedFstC;
    dut->trace(m_trace, 99);
    m_trace->open("wave/Adv2R1WURAM.fst");
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
        printf("\033[42;32mVerilator: Adv2R1WURAM Test PASS!\033[0m\n");
    } else {
        printf("\033[41;32mVerilator: Adv2R1WURAM Test FAILED!\033[0m\n");
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
