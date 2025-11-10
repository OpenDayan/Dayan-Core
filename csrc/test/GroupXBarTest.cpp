#include <stdlib.h>
#include <iostream>
#include <verilated.h>
#include "verilated_fst_c.h"
#include "VGroupXBar.h"
#include <vector>
#include <queue>
#define MAX_SIM_TIME 1000
VerilatedContext *contextp;
VGroupXBar *dut;
unsigned long long sim_time = 0;
unsigned int stopFlag = 0;

#define N 32

unsigned int reqArray[N];
static inline void initArray(int bank) {
    for(int i = 0 ; i < N ; i ++) {
        if(bank == -1) reqArray[i] = i;
        else reqArray[i] = (i & 0x3) | (bank * 4);
    }
}
static inline void rndInitArray() {
    for(int i = 0 ; i < N ; i ++) {
        reqArray[i] = rand();
    }
}
static inline void shuffle(unsigned int * start, int elemSize, int range) {
    // 计算实际的元素个数（以elemSize为单位）
    int numElements = range / elemSize;
    
    // Fisher-Yates 洗牌算法
    for (int i = numElements - 1; i > 0; i--) {
        // 生成 [0, i] 范围内的随机索引
        int j = rand() % (i + 1);
        
        // 交换第 i 个和第 j 个元素块
        if (i != j) {
            for (int k = 0; k < elemSize; k++) {
                unsigned int temp = start[i * elemSize + k];
                start[i * elemSize + k] = start[j * elemSize + k];
                start[j * elemSize + k] = temp;
            }
        }
    }
}

unsigned long long totalValid = 0;
unsigned long long totalFired = 0;

static inline void mainLoop()
{
    static int i = -1;
    // Random Assign 32 Request to Each Ports with no conflict!
    initArray(i++);
    // initArray(-1);
    // rndInitArray();
    for(int i = 0 ; i < N / 4; i++) {
        shuffle(reqArray + i * 4, 1, 4);
    }
    shuffle(reqArray, 4, N);
    // shuffle(reqArray, 1, N);

    dut->in_0_bits = reqArray[ 0 + 0];dut->in_1_bits = reqArray[ 0 + 1];dut->in_2_bits = reqArray[ 0 + 2];dut->in_3_bits = reqArray[ 0 + 3];
    dut->in_4_bits = reqArray[ 4 + 0];dut->in_5_bits = reqArray[ 4 + 1];dut->in_6_bits = reqArray[ 4 + 2];dut->in_7_bits = reqArray[ 4 + 3];
    dut->in_8_bits = reqArray[ 8 + 0];dut->in_9_bits = reqArray[ 8 + 1];dut->in_10_bits = reqArray[ 8 + 2];dut->in_11_bits = reqArray[ 8 + 3];
    dut->in_12_bits = reqArray[12 + 0];dut->in_13_bits = reqArray[12 + 1];dut->in_14_bits = reqArray[12 + 2];dut->in_15_bits = reqArray[12 + 3];
    dut->in_16_bits = reqArray[16 + 0];dut->in_17_bits = reqArray[16 + 1];dut->in_18_bits = reqArray[16 + 2];dut->in_19_bits = reqArray[16 + 3];
    dut->in_20_bits = reqArray[20 + 0];dut->in_21_bits = reqArray[20 + 1];dut->in_22_bits = reqArray[20 + 2];dut->in_23_bits = reqArray[20 + 3];
    dut->in_24_bits = reqArray[24 + 0];dut->in_25_bits = reqArray[24 + 1];dut->in_26_bits = reqArray[24 + 2];dut->in_27_bits = reqArray[24 + 3];
    dut->in_28_bits = reqArray[28 + 0];dut->in_29_bits = reqArray[28 + 1];dut->in_30_bits = reqArray[28 + 2];dut->in_31_bits = reqArray[28 + 3];

    dut->in_0_valid  = 1;dut->in_1_valid  = 1;dut->in_2_valid  = 1;dut->in_3_valid  = 1;
    dut->in_4_valid  = 1;dut->in_5_valid  = 1;dut->in_6_valid  = 1;dut->in_7_valid  = 1;
    dut->in_8_valid  = 1;dut->in_9_valid  = 1;dut->in_10_valid = 1;dut->in_11_valid = 1;
    dut->in_12_valid = 1;dut->in_13_valid = 1;dut->in_14_valid = 1;dut->in_15_valid = 1;
    dut->in_16_valid = 1;dut->in_17_valid = 1;dut->in_18_valid = 1;dut->in_19_valid = 1;
    dut->in_20_valid = 1;dut->in_21_valid = 1;dut->in_22_valid = 1;dut->in_23_valid = 1;
    dut->in_24_valid = 1;dut->in_25_valid = 1;dut->in_26_valid = 1;dut->in_27_valid = 1;
    dut->in_28_valid = 1;dut->in_29_valid = 1;dut->in_30_valid = 1;dut->in_31_valid = 1;

    totalValid += 32;
    totalFired += 
    dut->in_0_ready  + dut->in_1_ready  + dut->in_2_ready  + dut->in_3_ready +
    dut->in_4_ready  + dut->in_5_ready  + dut->in_6_ready  + dut->in_7_ready +
    dut->in_8_ready  + dut->in_9_ready  + dut->in_10_ready + dut->in_11_ready +
    dut->in_12_ready + dut->in_13_ready + dut->in_14_ready + dut->in_15_ready +
    dut->in_16_ready + dut->in_17_ready + dut->in_18_ready + dut->in_19_ready +
    dut->in_20_ready + dut->in_21_ready + dut->in_22_ready + dut->in_23_ready +
    dut->in_24_ready + dut->in_25_ready + dut->in_26_ready + dut->in_27_ready +
    dut->in_28_ready + dut->in_29_ready + dut->in_30_ready + dut->in_31_ready;

    dut->out_ready = 1;

}
int main(int argc, char **argv)
{
    contextp = new VerilatedContext;
    contextp->commandArgs(argc, argv);
    dut = new VGroupXBar;
    Verilated::traceEverOn(true);
    VerilatedFstC *m_trace = new VerilatedFstC;
    dut->trace(m_trace, 99);
    m_trace->open("wave/GroupXBar.fst");
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
    printf("Total Valid: %lld, Total Fired: %lld, Rate: %lf\n", totalValid, totalFired, (double)totalFired / (double)totalValid);
    if(!stopFlag) {
        printf("\033[42;32mVerilator: GroupXBar Test PASS!\033[0m\n");
    } else {
        printf("\033[41;32mVerilator: GroupXBar Test FAILED!\033[0m\n");
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
