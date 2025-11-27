#include <stdlib.h>
#include <iostream>
#include <verilated.h>
#include "verilated_fst_c.h"
#include "VKulischAccumulatorTestTop.h"
#include <vector>
#include <queue>
#include <cmath>
#include <cstring>
#include <iomanip>

#define MAX_SIM_TIME 1000
VerilatedContext *contextp;
VKulischAccumulatorTestTop *dut;
unsigned long long sim_time = 0;
unsigned int stopFlag = 0;
bool globalPass = true;

struct TestCase {
    float inputs[4];
    float expected;
    const char* name;
};

std::vector<TestCase> tests = {
    {{1.0f, 2.0f, 3.0f, 4.0f}, 10.0f, "Simple Sum"},
    {{1.0e20f, 1.0f, -1.0e20f, 0.0f}, 1.0f, "Cancellation 1"},
    {{1.5f, 1.0e10f, -1.0e10f, 2.5f}, 4.0f, "Cancellation 2"},
    {{10.0f, -5.0f, 2.5f, -0.5f}, 7.0f, "Mixed Signs"},
    {{0.0f, 0.0f, 0.0f, 0.0f}, 0.0f, "All Zeros"},
    {{1.0e-10f, 2.0e-10f, 3.0e-10f, 4.0e-10f}, 10.0e-10f, "Small Numbers"}
};

// Helper functions for float/bits conversion
uint32_t float2bits(float f) {
    uint32_t u;
    std::memcpy(&u, &f, sizeof(f));
    return u;
}

float bits2float(uint32_t u) {
    float f;
    std::memcpy(&f, &u, sizeof(f));
    return f;
}

static inline void mainLoop()
{
    static int testIdx = 0;
    
    // Check results from previous cycle
    if (testIdx > 0 && testIdx <= tests.size()) {
        int prevIdx = testIdx - 1;
        float out = bits2float(dut->io_out);
        float expected = tests[prevIdx].expected;
        float diff = std::abs(out - expected);
        
        bool pass = false;
        if (std::abs(expected) < 1e-30) {
            pass = diff < 1e-30; 
        } else {
            pass = diff / std::abs(expected) < 1e-6; 
        }

        if (!pass) {
             std::cout << "\033[41;32mFAIL: " << tests[prevIdx].name 
                  << " Expected: " << expected << " Got: " << out 
                  << " (Diff: " << diff << ")\033[0m" << std::endl;
             globalPass = false;
             stopFlag = 2; // Stop simulation
        } else {
             std::cout << "\033[42;32mPASS: " << tests[prevIdx].name << " Got: " << out << "\033[0m" << std::endl;
        }
    }
    
    // Apply next inputs
    if (testIdx < tests.size()) {
        if (stopFlag == 0) {
            dut->io_in_0 = float2bits(tests[testIdx].inputs[0]);
            dut->io_in_1 = float2bits(tests[testIdx].inputs[1]);
            dut->io_in_2 = float2bits(tests[testIdx].inputs[2]);
            dut->io_in_3 = float2bits(tests[testIdx].inputs[3]);
        }
    } else {
        // Finish simulation after last check
        if (stopFlag == 0) stopFlag = 2;
    }
    
    testIdx++;
}

int main(int argc, char **argv)
{
    contextp = new VerilatedContext;
    contextp->commandArgs(argc, argv);
    dut = new VKulischAccumulatorTestTop;
    Verilated::traceEverOn(true);
    VerilatedFstC *m_trace = new VerilatedFstC;
    dut->trace(m_trace, 99);
    m_trace->open("wave/KulischAccumulatorTestTop.fst");
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
    if(globalPass) {
        printf("\033[42;32mVerilator: KulischAccumulatorTestTop Test PASS!\033[0m\n");
    } else {
        printf("\033[41;32mVerilator: KulischAccumulatorTestTop Test FAILED!\033[0m\n");
    }
    m_trace->close();
    delete dut;
    exit(EXIT_SUCCESS);
}

void vl_fatal(const char* filename, int line, const char* func, const char* msg) {
    std::cerr << "Fatal error occurred at " << filename << ":" << line << " (function: " << func << ") - " << msg << std::endl;
    stopFlag = 5;
    globalPass = false;
}

void vl_stop(const char* filename, int line, const char* func) {
    std::cerr << "stop occurred at " << filename << ":" << line << " (function: " << func << ")" << std::endl;
    stopFlag = 5;
    globalPass = false;
}
