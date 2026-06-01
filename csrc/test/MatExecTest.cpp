#include <stdlib.h>
#include <iostream>
#include <verilated.h>
#include "verilated_fst_c.h"
#include "VMatExec.h"

#define MAX_SIM_TIME 300

VerilatedContext *contextp;
VMatExec *dut;
VerilatedFstC *m_trace;
unsigned long long sim_time = 0;
unsigned int stopFlag = 0;

static inline void fail(const char *msg)
{
    std::cerr << "MatExecTest: " << msg << std::endl;
    if(!stopFlag) stopFlag = 5;
}

static inline void failEq(const char *name, unsigned long long exp, unsigned long long got)
{
    std::cerr << "MatExecTest: " << name
              << " expected 0x" << std::hex << exp
              << " got 0x" << got << std::dec << std::endl;
    if(!stopFlag) stopFlag = 5;
}

#define CLEAR_A_RESP(CH) do { \
    dut->io_busAResp_##CH##_valid = 0; \
    dut->io_busAResp_##CH##_regId = 0; \
    dut->io_busAResp_##CH##_zero = 0; \
    dut->io_busAResp_##CH##_byteZeroMask = 0; \
} while(0)

#define CLEAR_B_RESP(CH) do { \
    dut->io_busBResp_##CH##_valid = 0; \
    dut->io_busBResp_##CH##_regId = 0; \
    dut->io_busBResp_##CH##_zero = 0; \
    dut->io_busBResp_##CH##_byteZeroMask = 0; \
} while(0)

#define ZERO_A_RESP(CH, REGID) do { \
    dut->io_busAResp_##CH##_valid = 1; \
    dut->io_busAResp_##CH##_regId = (REGID); \
    dut->io_busAResp_##CH##_zero = 1; \
    dut->io_busAResp_##CH##_byteZeroMask = 0; \
} while(0)

#define ZERO_B_RESP(CH, REGID) do { \
    dut->io_busBResp_##CH##_valid = 1; \
    dut->io_busBResp_##CH##_regId = (REGID); \
    dut->io_busBResp_##CH##_zero = 1; \
    dut->io_busBResp_##CH##_byteZeroMask = 0; \
} while(0)

static inline void clearResponses()
{
    CLEAR_A_RESP(0); CLEAR_A_RESP(1); CLEAR_A_RESP(2); CLEAR_A_RESP(3);
    CLEAR_A_RESP(4); CLEAR_A_RESP(5); CLEAR_A_RESP(6); CLEAR_A_RESP(7);
    CLEAR_B_RESP(0); CLEAR_B_RESP(1); CLEAR_B_RESP(2); CLEAR_B_RESP(3);
    CLEAR_B_RESP(4); CLEAR_B_RESP(5); CLEAR_B_RESP(6); CLEAR_B_RESP(7);
}

static inline void setDefaults()
{
    dut->io_ldA_valid = 0;
    dut->io_ldA_bits_regId = 0;
    dut->io_ldA_bits_addr = 0;
    dut->io_ldA_bits_stride = 0;
    dut->io_ldA_bits_swizzle = 0;
    dut->io_ldA_bits_zeroMask = 0;
    dut->io_ldA_bits_byteZeroMask = 0;
    dut->io_ldB_valid = 0;
    dut->io_ldB_bits_regId = 0;
    dut->io_ldB_bits_addr = 0;
    dut->io_ldB_bits_stride = 0;
    dut->io_ldB_bits_swizzle = 0;
    dut->io_ldB_bits_zeroMask = 0;
    dut->io_ldB_bits_byteZeroMask = 0;
    dut->io_calc_valid = 0;
    dut->io_calc_bits_regA = 0;
    dut->io_calc_bits_regB = 0;
    dut->io_calc_bits_regC = 0;
    dut->io_calc_bits_releaseA = 0;
    dut->io_calc_bits_releaseB = 0;
    dut->io_calc_bits_bf16 = 0;
    dut->io_calc_bits_clear = 0;
    dut->io_calc_bits_writeback = 0;
    dut->io_result_ready = 1;
    dut->io_busAReq_ready = 1;
    dut->io_busBReq_ready = 1;
    dut->io_busAAck = 0;
    dut->io_busBAck = 0;
    clearResponses();
}

static inline void tick()
{
    dut->clock = 0;
    dut->eval();
    m_trace->dump(contextp->time());
    contextp->timeInc(1);
    dut->clock = 1;
    dut->eval();
    m_trace->dump(contextp->time());
    contextp->timeInc(1);
    sim_time++;
}

static inline void issueLoadA(unsigned int regId)
{
    setDefaults();
    dut->io_ldA_valid = 1;
    dut->io_ldA_bits_regId = regId;
    dut->io_ldA_bits_addr = 0x100;
    dut->io_ldA_bits_stride = 4;
    dut->eval();
    if(!dut->io_ldA_ready) fail("ldA should be ready before accepting a load command");
    tick();

    dut->io_ldA_valid = 0;
    dut->eval();
    if(!dut->io_busAReq_valid) fail("busAReq should become valid after ldA");
    if(dut->io_busAReq_bits_regId != regId) failEq("busAReq.regId", regId, dut->io_busAReq_bits_regId);
    if(dut->io_busAReq_bits_addr != 0x100) failEq("busAReq.addr", 0x100, dut->io_busAReq_bits_addr);
    if(!dut->io_busAReq_bits_last) fail("busAReq.last should be set for the current tile shape");

    ZERO_A_RESP(0, regId); ZERO_A_RESP(1, regId); ZERO_A_RESP(2, regId); ZERO_A_RESP(3, regId);
    ZERO_A_RESP(4, regId); ZERO_A_RESP(5, regId); ZERO_A_RESP(6, regId); ZERO_A_RESP(7, regId);
    dut->io_busAAck = 1;
    tick();
    if(!dut->io_ldAAck) fail("ldAAck should mirror busAAck");
}

static inline void issueLoadB(unsigned int regId)
{
    setDefaults();
    dut->io_ldB_valid = 1;
    dut->io_ldB_bits_regId = regId;
    dut->io_ldB_bits_addr = 0x200;
    dut->io_ldB_bits_stride = 4;
    dut->eval();
    if(!dut->io_ldB_ready) fail("ldB should be ready before accepting a load command");
    tick();

    dut->io_ldB_valid = 0;
    dut->eval();
    if(!dut->io_busBReq_valid) fail("busBReq should become valid after ldB");
    if(dut->io_busBReq_bits_regId != regId) failEq("busBReq.regId", regId, dut->io_busBReq_bits_regId);
    if(dut->io_busBReq_bits_addr != 0x200) failEq("busBReq.addr", 0x200, dut->io_busBReq_bits_addr);
    if(!dut->io_busBReq_bits_last) fail("busBReq.last should be set for the current tile shape");

    ZERO_B_RESP(0, regId); ZERO_B_RESP(1, regId); ZERO_B_RESP(2, regId); ZERO_B_RESP(3, regId);
    ZERO_B_RESP(4, regId); ZERO_B_RESP(5, regId); ZERO_B_RESP(6, regId); ZERO_B_RESP(7, regId);
    dut->io_busBAck = 1;
    tick();
    if(!dut->io_ldBAck) fail("ldBAck should mirror busBAck");
}

int main(int argc, char **argv)
{
    contextp = new VerilatedContext;
    contextp->commandArgs(argc, argv);
    dut = new VMatExec;
    Verilated::traceEverOn(true);
    m_trace = new VerilatedFstC;
    dut->trace(m_trace, 99);
    m_trace->open("wave/MatExec.fst");

    dut->clock = 0;
    dut->reset = 1;
    setDefaults();
    for(int i = 0; i < 10; i++) tick();
    dut->reset = 0;
    tick();

    issueLoadA(1);
    issueLoadB(2);

    setDefaults();
    dut->io_calc_valid = 1;
    dut->io_calc_bits_regA = 1;
    dut->io_calc_bits_regB = 2;
    dut->io_calc_bits_regC = 0;
    dut->io_calc_bits_releaseA = 1;
    dut->io_calc_bits_releaseB = 1;
    dut->io_calc_bits_clear = 1;
    dut->io_calc_bits_writeback = 1;
    dut->eval();
    if(!dut->io_calc_ready) fail("calc should be ready for the first C register use");
    if(!dut->io_ldARelease || !dut->io_ldBRelease) {
        fail("ldARelease/ldBRelease should assert with calc fire and release bits");
    }
    tick();

    dut->io_calc_valid = 0;
    bool sawResult = false;
    for(int i = 0; i < 40; i++) {
        setDefaults();
        tick();
        if(dut->io_result_valid) {
            sawResult = true;
        }
    }
    if(!sawResult) fail("result.valid did not assert after calc");

    if(!stopFlag) {
        printf("\033[42;32mVerilator: MatExec Test PASS!\033[0m\n");
    } else {
        printf("\033[41;32mVerilator: MatExec Test FAILED!\033[0m\n");
    }
    m_trace->close();
    delete dut;
    delete m_trace;
    delete contextp;
    exit(stopFlag ? EXIT_FAILURE : EXIT_SUCCESS);
}

void vl_fatal(const char* filename, int line, const char* func, const char* msg) {
    std::cerr << "Fatal error occurred at " << filename << ":" << line
              << " (function: " << func << ") - " << msg << std::endl;
    stopFlag = 5;
}

void vl_stop(const char* filename, int line, const char* func) {
    std::cerr << "stop occurred at " << filename << ":" << line
              << " (function: " << func << ")" << std::endl;
    stopFlag = 5;
}
