#include <stdlib.h>
#include <iostream>
#include <verilated.h>
#include "verilated_fst_c.h"
#include "VDMAReorderTransposeBuffer.h"
#include <set>
#include <queue>
#define MAX_SIM_TIME 1000
VerilatedContext *contextp;
VDMAReorderTransposeBuffer *dut;
unsigned long long sim_time = 0;
unsigned int stopFlag = 0;

// 生成大量的请求, 随机响应
std::set<int> ids;

static inline void mainLoop()
{
    dut->groupReq_valid = 1;
    // dut->groupReq_bits_keepInfo = sim_time & 0xffffffff;
    dut->groupReq_bits_groupSz = 127; // MaxSize
    dut->groupReq_bits_groupPad = 95;
    dut->groupReq_bits_reqPad = 31;
    dut->groupReq_bits_padValue_0 = 0xffffffff;    dut->groupReq_bits_padValue_1 = 0xffffffff;
    dut->groupReq_bits_padValue_2 = 0xffffffff;    dut->groupReq_bits_padValue_3 = 0xffffffff;
    dut->groupReq_bits_padValue_4 = 0xffffffff;    dut->groupReq_bits_padValue_5 = 0xffffffff;
    dut->groupReq_bits_padValue_6 = 0xffffffff;    dut->groupReq_bits_padValue_7 = 0xffffffff;
    dut->groupReq_bits_transpose = 3;

    if(ids.size() > 5 && (rand() % 10) > 1) {
        std::set<int>::const_iterator it(ids.begin());
        std::advance(it, rand() % ids.size());
        int sel = *it;
        int hi = sel << 8;
        ids.erase(it);
        dut->transactionComp_valid = 1;
        dut->transactionComp_bits_tid = sel;
        dut->transactionComp_bits_data_0 = (0x00 | hi) & 0xffffffff;
        dut->transactionComp_bits_data_1 = (0x01 | hi) & 0xffffffff;
        dut->transactionComp_bits_data_2 = (0x02 | hi) & 0xffffffff;
        dut->transactionComp_bits_data_3 = (0x03 | hi) & 0xffffffff;
        dut->transactionComp_bits_data_4 = (0x04 | hi) & 0xffffffff;
        dut->transactionComp_bits_data_5 = (0x05 | hi) & 0xffffffff;
        dut->transactionComp_bits_data_6 = (0x06 | hi) & 0xffffffff;
        dut->transactionComp_bits_data_7 = (0x07 | hi) & 0xffffffff;
        dut->transactionComp_bits_data_8 = (0x10 | hi) & 0xffffffff;
        dut->transactionComp_bits_data_9 = (0x11 | hi) & 0xffffffff;
        dut->transactionComp_bits_data_10 = (0x12 | hi) & 0xffffffff;
        dut->transactionComp_bits_data_11 = (0x13 | hi) & 0xffffffff;
        dut->transactionComp_bits_data_12 = (0x14 | hi) & 0xffffffff;
        dut->transactionComp_bits_data_13 = (0x15 | hi) & 0xffffffff;
        dut->transactionComp_bits_data_14 = (0x16 | hi) & 0xffffffff;
        dut->transactionComp_bits_data_15 = (0x17 | hi) & 0xffffffff;
        dut->transactionComp_bits_data_16 = (0x20 | hi) & 0xffffffff;
        dut->transactionComp_bits_data_17 = (0x21 | hi) & 0xffffffff;
        dut->transactionComp_bits_data_18 = (0x22 | hi) & 0xffffffff;
        dut->transactionComp_bits_data_19 = (0x23 | hi) & 0xffffffff;
        dut->transactionComp_bits_data_20 = (0x24 | hi) & 0xffffffff;
        dut->transactionComp_bits_data_21 = (0x25 | hi) & 0xffffffff;
        dut->transactionComp_bits_data_22 = (0x26 | hi) & 0xffffffff;
        dut->transactionComp_bits_data_23 = (0x27 | hi) & 0xffffffff;
        dut->transactionComp_bits_data_24 = (0x30 | hi) & 0xffffffff;
        dut->transactionComp_bits_data_25 = (0x31 | hi) & 0xffffffff;
        dut->transactionComp_bits_data_26 = (0x32 | hi) & 0xffffffff;
        dut->transactionComp_bits_data_27 = (0x33 | hi) & 0xffffffff;
        dut->transactionComp_bits_data_28 = (0x34 | hi) & 0xffffffff;
        dut->transactionComp_bits_data_29 = (0x35 | hi) & 0xffffffff;
        dut->transactionComp_bits_data_30 = (0x36 | hi) & 0xffffffff;
        dut->transactionComp_bits_data_31 = (0x37 | hi) & 0xffffffff;
        dut->transactionComp_bits_data_32 = (0x40 | hi) & 0xffffffff;
        dut->transactionComp_bits_data_33 = (0x41 | hi) & 0xffffffff;
        dut->transactionComp_bits_data_34 = (0x42 | hi) & 0xffffffff;
        dut->transactionComp_bits_data_35 = (0x43 | hi) & 0xffffffff;
        dut->transactionComp_bits_data_36 = (0x44 | hi) & 0xffffffff;
        dut->transactionComp_bits_data_37 = (0x45 | hi) & 0xffffffff;
        dut->transactionComp_bits_data_38 = (0x46 | hi) & 0xffffffff;
        dut->transactionComp_bits_data_39 = (0x47 | hi) & 0xffffffff;
        dut->transactionComp_bits_data_40 = (0x50 | hi) & 0xffffffff;
        dut->transactionComp_bits_data_41 = (0x51 | hi) & 0xffffffff;
        dut->transactionComp_bits_data_42 = (0x52 | hi) & 0xffffffff;
        dut->transactionComp_bits_data_43 = (0x53 | hi) & 0xffffffff;
        dut->transactionComp_bits_data_44 = (0x54 | hi) & 0xffffffff;
        dut->transactionComp_bits_data_45 = (0x55 | hi) & 0xffffffff;
        dut->transactionComp_bits_data_46 = (0x56 | hi) & 0xffffffff;
        dut->transactionComp_bits_data_47 = (0x57 | hi) & 0xffffffff;
        dut->transactionComp_bits_data_48 = (0x60 | hi) & 0xffffffff;
        dut->transactionComp_bits_data_49 = (0x61 | hi) & 0xffffffff;
        dut->transactionComp_bits_data_50 = (0x62 | hi) & 0xffffffff;
        dut->transactionComp_bits_data_51 = (0x63 | hi) & 0xffffffff;
        dut->transactionComp_bits_data_52 = (0x64 | hi) & 0xffffffff;
        dut->transactionComp_bits_data_53 = (0x65 | hi) & 0xffffffff;
        dut->transactionComp_bits_data_54 = (0x66 | hi) & 0xffffffff;
        dut->transactionComp_bits_data_55 = (0x67 | hi) & 0xffffffff;
        dut->transactionComp_bits_data_56 = (0x70 | hi) & 0xffffffff;
        dut->transactionComp_bits_data_57 = (0x71 | hi) & 0xffffffff;
        dut->transactionComp_bits_data_58 = (0x72 | hi) & 0xffffffff;
        dut->transactionComp_bits_data_59 = (0x73 | hi) & 0xffffffff;
        dut->transactionComp_bits_data_60 = (0x74 | hi) & 0xffffffff;
        dut->transactionComp_bits_data_61 = (0x75 | hi) & 0xffffffff;
        dut->transactionComp_bits_data_62 = (0x76 | hi) & 0xffffffff;
        dut->transactionComp_bits_data_63 = (0x77 | hi) & 0xffffffff;
    } else {
        dut->transactionComp_valid = 0;
    }

    dut->transactionAlloc_ready = rand() & 1;
    if(dut->transactionAlloc_ready & dut->transactionAlloc_valid) {
        int id = dut->transactionAlloc_bits;
        ids.insert(id);
        // printf("Get %d\n", id);
    }
}
int main(int argc, char **argv)
{
    contextp = new VerilatedContext;
    contextp->commandArgs(argc, argv);
    dut = new VDMAReorderTransposeBuffer;
    Verilated::traceEverOn(true);
    VerilatedFstC *m_trace = new VerilatedFstC;
    dut->trace(m_trace, 99);
    m_trace->open("wave/DMAReorderTransposeBuffer.fst");
    dut->clock = 0;
    dut->reset = 1;
    dut->outStream_ready = 1;
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
        printf("\033[42;32mVerilator: DMAReorderTransposeBuffer Test PASS!\033[0m\n");
    } else {
        printf("\033[41;32mVerilator: DMAReorderTransposeBuffer Test FAILED!\033[0m\n");
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
