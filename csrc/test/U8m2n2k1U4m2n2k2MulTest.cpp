#include <stdlib.h>
#include <iostream>
#include <verilated.h>
#include "verilated_fst_c.h"
#include "VU8m2n2k1U4m2n2k2Mul.h"

#include <vector>
#include <queue>

#define MAX_SIM_TIME 1000

int main(int argc, char** argv) {
    VerilatedContext* contextp = new VerilatedContext;
    contextp->commandArgs(argc, argv);
    VU8m2n2k1U4m2n2k2Mul *dut = new VU8m2n2k1U4m2n2k2Mul;
    Verilated::traceEverOn(true);
    VerilatedFstC * m_trace = new VerilatedFstC;
    dut->trace(m_trace, 99);
    m_trace->open("wave/VU8m2n2k1U4m2n2k2Mul.fst");

    dut->clock = 0;
    dut->reset = 1;
    dut->eval();
    m_trace->dump(contextp->time());
    contextp->timeInc(1);
    for(int i = 0 ; i < 20; i++) {
        dut->clock ^= 1;
        dut->eval();
        m_trace->dump(contextp->time());
        contextp->timeInc(1);
    }
    dut->reset = 0;

    unsigned long long sim_time = 0;

    std::queue<bool> u8;
    std::queue<std::vector<u_int16_t>> result;

    while(sim_time < MAX_SIM_TIME) {
        dut->clock ^= 1;
        dut->eval();
        if((unsigned) rand() % 2) {
            // Core Test Bench
            dut->u8 = 0;
            dut->a_u4_0_0 = (unsigned)rand() % 16;
            dut->a_u4_0_1 = (unsigned)rand() % 16;
            dut->a_u4_1_0 = (unsigned)rand() % 16;
            dut->a_u4_1_1 = (unsigned)rand() % 16;
            dut->b_u4_0_0 = (unsigned)rand() % 16;
            dut->b_u4_0_1 = (unsigned)rand() % 16;
            dut->b_u4_1_0 = (unsigned)rand() % 16;
            dut->b_u4_1_1 = (unsigned)rand() % 16;
            uint16_t t[8] = {
                (u_int16_t) dut->a_u4_0_0 * dut->b_u4_0_0, // 0 0 0
                (u_int16_t) dut->a_u4_0_1 * dut->b_u4_0_1, // 0 0 1
                (u_int16_t) dut->a_u4_0_0 * dut->b_u4_1_0, // 0 1 0
                (u_int16_t) dut->a_u4_0_1 * dut->b_u4_1_1, // 0 1 1
                (u_int16_t) dut->a_u4_1_0 * dut->b_u4_0_0, // 1 0 0
                (u_int16_t) dut->a_u4_1_1 * dut->b_u4_0_1, // 1 0 1
                (u_int16_t) dut->a_u4_1_0 * dut->b_u4_1_0, // 1 1 0
                (u_int16_t) dut->a_u4_1_1 * dut->b_u4_1_1, // 1 1 1
            };
            std::vector<u_int16_t> d(std::begin(t), std::end(t));
            result.push(d);
            u8.push(false);
        } else {
            dut->u8 = 1;
            dut->a_u8_0 = (unsigned)rand() % 256;
            dut->a_u8_1 = (unsigned)rand() % 256;
            dut->b_u8_0 = (unsigned)rand() % 256;
            dut->b_u8_1 = (unsigned)rand() % 256;
            uint16_t t[4] = {
                (u_int16_t) dut->a_u8_0 * dut->b_u8_0, // 0 0
                (u_int16_t) dut->a_u8_0 * dut->b_u8_1, // 0 1
                (u_int16_t) dut->a_u8_1 * dut->b_u8_0, // 1 0
                (u_int16_t) dut->a_u8_1 * dut->b_u8_1, // 1 1
            };
            std::vector<u_int16_t> d(std::begin(t), std::end(t));
            result.push(d);
            u8.push(true);
        }

        if(sim_time >= 2) {
            if(u8.front() == false) {
                auto t = result.front();
                if(
                    t[0] != dut->u4_out_0_0_0 ||
                    t[1] != dut->u4_out_0_0_1 ||
                    t[2] != dut->u4_out_0_1_0 ||
                    t[3] != dut->u4_out_0_1_1 ||
                    t[4] != dut->u4_out_1_0_0 ||
                    t[5] != dut->u4_out_1_0_1 ||
                    t[6] != dut->u4_out_1_1_0 ||
                    t[7] != dut->u4_out_1_1_1
                ) {
                    printf("Not Match!\n");
                    printf("%d %d %d %d != %d %d %d %d\n",
                        t[0], t[1], t[2], t[3],
                        dut->u4_out_0_0_0,dut->u4_out_0_0_1,dut->u4_out_0_1_0,dut->u4_out_0_1_1);
                    printf("%d %d %d %d != %d %d %d %d\n",
                        t[4], t[5], t[6], t[7],
                        dut->u4_out_1_0_0,dut->u4_out_1_0_1,dut->u4_out_1_1_0,dut->u4_out_1_1_1);
                }
            } else {
                auto t = result.front();
                if(
                    t[0] != dut->u8_out_0_0 ||
                    t[1] != dut->u8_out_0_1 ||
                    t[2] != dut->u8_out_1_0 ||
                    t[3] != dut->u8_out_1_1
                ) {
                    printf("U8 Not Match!\n");
                    printf("%d %d %d %d != %d %d %d %d\n",
                        t[0], t[1], t[2], t[3],
                        dut->u8_out_0_0,dut->u8_out_0_1,dut->u8_out_1_0,dut->u8_out_1_1);
                }
            }
            u8.pop();
            result.pop();
        }

        m_trace->dump(contextp->time());
        contextp->timeInc(1);
        dut->clock ^= 1;
        dut->eval();
        m_trace->dump(contextp->time());
        contextp->timeInc(1);
        sim_time++;
    }

    m_trace->close();
    delete dut;
    exit(EXIT_SUCCESS);
}
