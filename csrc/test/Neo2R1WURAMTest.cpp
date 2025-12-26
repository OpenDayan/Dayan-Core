#include <stdlib.h>
#include <iostream>
#include <verilated.h>
#include "verilated_fst_c.h"
#include "VNeo2R1WURAM.h"
#include <vector>
#include <queue>
#define MAX_SIM_TIME 1000
VerilatedContext *contextp;
VNeo2R1WURAM *dut;
unsigned long long sim_time = 0;
unsigned int stopFlag = 0;

extern "C" long long UltraRamRead(int id, int raddr)
{
    return id | (raddr << 2);
}

extern "C" long long UltraRamWrite(int id, int byte_enable, int waddr, long long wdata)
{
    return 0;
}

#define REQ(Group, Port, Field) (dut->io_r##Group##Req_##Port##_##Field)
#define RNDREQ(Group, Port) \
    do { \
        REQ(Group, Port, valid) = 1; \
        REQ(Group, Port, bits_address) = rand() & ((1 << 14) - 1); \
        REQ(Group, Port, bits_id)      = 0; \
    } while(0)

static inline void mainLoop()
{
    if(sim_time < 80) {
        RNDREQ(0, 0);
        RNDREQ(0, 1);
        RNDREQ(0, 2);
        RNDREQ(0, 3);
        RNDREQ(0, 4);
        RNDREQ(0, 5);
        RNDREQ(0, 6);
        RNDREQ(0, 7);
        RNDREQ(1, 0);
        RNDREQ(1, 1);
        RNDREQ(1, 2);
        RNDREQ(1, 3);
        RNDREQ(1, 4);
        RNDREQ(1, 5);
        RNDREQ(1, 6);
        RNDREQ(1, 7);
    } else {
        REQ(0, 0, valid) = 0;
        REQ(0, 1, valid) = 0;
        REQ(0, 2, valid) = 0;
        REQ(0, 3, valid) = 0;
        REQ(0, 4, valid) = 0;
        REQ(0, 5, valid) = 0;
        REQ(0, 6, valid) = 0;
        REQ(0, 7, valid) = 0;
        REQ(1, 0, valid) = 0;
        REQ(1, 1, valid) = 0;
        REQ(1, 2, valid) = 0;
        REQ(1, 3, valid) = 0;
        REQ(1, 4, valid) = 0;
        REQ(1, 5, valid) = 0;
        REQ(1, 6, valid) = 0;
        REQ(1, 7, valid) = 0;
    }
    dut->io_r0Resp_0_ready = 1;
    dut->io_r0Resp_1_ready = 1;
    dut->io_r0Resp_2_ready = 1;
    dut->io_r0Resp_3_ready = 1;
    dut->io_r1Resp_0_ready = 1;
    dut->io_r1Resp_1_ready = 1;
    dut->io_r1Resp_2_ready = 1;
    dut->io_r1Resp_3_ready = 1;
}
int main(int argc, char **argv)
{
    contextp = new VerilatedContext;
    contextp->commandArgs(argc, argv);
    dut = new VNeo2R1WURAM;
    Verilated::traceEverOn(true);
    VerilatedFstC *m_trace = new VerilatedFstC;
    dut->trace(m_trace, 99);
    m_trace->open("wave/Neo2R1WURAM.fst");
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
        printf("\033[42;32mVerilator: Neo2R1WURAM Test PASS!\033[0m\n");
    } else {
        printf("\033[41;32mVerilator: Neo2R1WURAM Test FAILED!\033[0m\n");
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
