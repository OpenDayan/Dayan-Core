#include <stdlib.h>
#include <iostream>
#include <verilated.h>
#include "verilated_fst_c.h"
#include "VMatTileRegisters.h"

#define MAX_SIM_TIME 200

VerilatedContext *contextp;
VMatTileRegisters *dut;
VerilatedFstC *m_trace;
unsigned long long sim_time = 0;
unsigned int stopFlag = 0;

static inline void fail(const char *msg)
{
    std::cerr << "MatTileRegistersTest: " << msg << std::endl;
    if(!stopFlag) stopFlag = 5;
}

static inline void failEq(const char *name, unsigned long long exp, unsigned long long got)
{
    std::cerr << "MatTileRegistersTest: " << name
              << " expected 0x" << std::hex << exp
              << " got 0x" << got << std::dec << std::endl;
    if(!stopFlag) stopFlag = 5;
}

#define CLEAR_RESP(CH) do { \
    dut->io_busResp_##CH##_valid = 0; \
    dut->io_busResp_##CH##_regId = 0; \
    dut->io_busResp_##CH##_zero = 0; \
    dut->io_busResp_##CH##_byteZeroMask = 0; \
} while(0)

#define SET_RESP0_DATA(BASE) do { \
    dut->io_busResp_0_data_0 = (BASE) + 0; \
    dut->io_busResp_0_data_1 = (BASE) + 1; \
    dut->io_busResp_0_data_2 = (BASE) + 2; \
    dut->io_busResp_0_data_3 = (BASE) + 3; \
    dut->io_busResp_0_data_4 = (BASE) + 4; \
    dut->io_busResp_0_data_5 = (BASE) + 5; \
    dut->io_busResp_0_data_6 = (BASE) + 6; \
    dut->io_busResp_0_data_7 = (BASE) + 7; \
    dut->io_busResp_0_data_8 = (BASE) + 8; \
    dut->io_busResp_0_data_9 = (BASE) + 9; \
    dut->io_busResp_0_data_10 = (BASE) + 10; \
    dut->io_busResp_0_data_11 = (BASE) + 11; \
    dut->io_busResp_0_data_12 = (BASE) + 12; \
    dut->io_busResp_0_data_13 = (BASE) + 13; \
    dut->io_busResp_0_data_14 = (BASE) + 14; \
    dut->io_busResp_0_data_15 = (BASE) + 15; \
    dut->io_busResp_0_data_16 = (BASE) + 16; \
    dut->io_busResp_0_data_17 = (BASE) + 17; \
    dut->io_busResp_0_data_18 = (BASE) + 18; \
    dut->io_busResp_0_data_19 = (BASE) + 19; \
    dut->io_busResp_0_data_20 = (BASE) + 20; \
    dut->io_busResp_0_data_21 = (BASE) + 21; \
    dut->io_busResp_0_data_22 = (BASE) + 22; \
    dut->io_busResp_0_data_23 = (BASE) + 23; \
    dut->io_busResp_0_data_24 = (BASE) + 24; \
    dut->io_busResp_0_data_25 = (BASE) + 25; \
    dut->io_busResp_0_data_26 = (BASE) + 26; \
    dut->io_busResp_0_data_27 = (BASE) + 27; \
    dut->io_busResp_0_data_28 = (BASE) + 28; \
    dut->io_busResp_0_data_29 = (BASE) + 29; \
    dut->io_busResp_0_data_30 = (BASE) + 30; \
    dut->io_busResp_0_data_31 = (BASE) + 31; \
} while(0)

#define CHECK_DATA(IDX) do { \
    if(dut->io_data_##IDX != expected[IDX]) { \
        char name[32]; \
        snprintf(name, sizeof(name), "io_data_%d", IDX); \
        failEq(name, expected[IDX], dut->io_data_##IDX); \
    } \
} while(0)

static inline void setDefaults()
{
    dut->io_cmdIn_valid = 0;
    dut->io_cmdIn_bits_regId = 0;
    dut->io_cmdIn_bits_addr = 0;
    dut->io_cmdIn_bits_stride = 0;
    dut->io_cmdIn_bits_swizzle = 0;
    dut->io_cmdIn_bits_zeroMask = 0;
    dut->io_cmdIn_bits_byteZeroMask = 0;
    dut->io_busReq_ready = 1;
    CLEAR_RESP(0);
    CLEAR_RESP(1);
    CLEAR_RESP(2);
    CLEAR_RESP(3);
    CLEAR_RESP(4);
    CLEAR_RESP(5);
    CLEAR_RESP(6);
    CLEAR_RESP(7);
    dut->io_re = 0;
    dut->io_regId = 0;
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

static inline void checkData(const unsigned char expected[32])
{
    CHECK_DATA(0);
    CHECK_DATA(1);
    CHECK_DATA(2);
    CHECK_DATA(3);
    CHECK_DATA(4);
    CHECK_DATA(5);
    CHECK_DATA(6);
    CHECK_DATA(7);
    CHECK_DATA(8);
    CHECK_DATA(9);
    CHECK_DATA(10);
    CHECK_DATA(11);
    CHECK_DATA(12);
    CHECK_DATA(13);
    CHECK_DATA(14);
    CHECK_DATA(15);
    CHECK_DATA(16);
    CHECK_DATA(17);
    CHECK_DATA(18);
    CHECK_DATA(19);
    CHECK_DATA(20);
    CHECK_DATA(21);
    CHECK_DATA(22);
    CHECK_DATA(23);
    CHECK_DATA(24);
    CHECK_DATA(25);
    CHECK_DATA(26);
    CHECK_DATA(27);
    CHECK_DATA(28);
    CHECK_DATA(29);
    CHECK_DATA(30);
    CHECK_DATA(31);
}

static inline void issueLoadCmd(unsigned int regId, unsigned int addr,
                                unsigned int stride, unsigned int swizzle,
                                unsigned int zeroMask, unsigned int byteZeroMask)
{
    setDefaults();
    dut->io_cmdIn_valid = 1;
    dut->io_cmdIn_bits_regId = regId;
    dut->io_cmdIn_bits_addr = addr;
    dut->io_cmdIn_bits_stride = stride;
    dut->io_cmdIn_bits_swizzle = swizzle;
    dut->io_cmdIn_bits_zeroMask = zeroMask;
    dut->io_cmdIn_bits_byteZeroMask = byteZeroMask;
    dut->eval();
    if(!dut->io_cmdIn_ready) fail("cmdIn should be ready before accepting a load command");
    tick();

    dut->io_cmdIn_valid = 0;
    dut->eval();
    if(!dut->io_busReq_valid) fail("busReq should become valid after accepting cmdIn");
    if(dut->io_busReq_bits_regId != regId) failEq("busReq.regId", regId, dut->io_busReq_bits_regId);
    if(dut->io_busReq_bits_addr != addr) failEq("busReq.addr", addr, dut->io_busReq_bits_addr);
    if(dut->io_busReq_bits_stride != stride) failEq("busReq.stride", stride, dut->io_busReq_bits_stride);
    if(dut->io_busReq_bits_swizzle != swizzle) failEq("busReq.swizzle", swizzle, dut->io_busReq_bits_swizzle);
    if(dut->io_busReq_bits_zeroMask != zeroMask) failEq("busReq.zeroMask", zeroMask, dut->io_busReq_bits_zeroMask);
    if(dut->io_busReq_bits_byteZeroMask != byteZeroMask) {
        failEq("busReq.byteZeroMask", byteZeroMask, dut->io_busReq_bits_byteZeroMask);
    }
    if(!dut->io_busReq_bits_last) fail("single-transaction tile request should assert last");
    tick();
}

int main(int argc, char **argv)
{
    contextp = new VerilatedContext;
    contextp->commandArgs(argc, argv);
    dut = new VMatTileRegisters;
    Verilated::traceEverOn(true);
    m_trace = new VerilatedFstC;
    dut->trace(m_trace, 99);
    m_trace->open("wave/MatTileRegisters.fst");

    dut->clock = 0;
    dut->reset = 1;
    setDefaults();
    for(int i = 0; i < 10; i++) tick();
    dut->reset = 0;
    tick();

    issueLoadCmd(3, 0x120, 4, 0x55aa, 0xa5, 0x3);

    setDefaults();
    dut->io_busResp_0_valid = 1;
    dut->io_busResp_0_regId = 3;
    dut->io_busResp_0_byteZeroMask = (1u << 0) | (1u << 5);
    SET_RESP0_DATA(0x40);
    tick();

    setDefaults();
    dut->io_re = 1;
    dut->io_regId = 3;
    tick();
    unsigned char expected[32];
    for(int i = 0; i < 32; i++) expected[i] = static_cast<unsigned char>(0x40 + i);
    expected[0] = 0;
    expected[5] = 0;
    checkData(expected);

    issueLoadCmd(4, 0x220, 8, 0x123456, 0x0, 0x0);

    setDefaults();
    dut->io_busResp_0_valid = 1;
    dut->io_busResp_0_regId = 4;
    dut->io_busResp_0_zero = 1;
    SET_RESP0_DATA(0x80);
    tick();

    setDefaults();
    dut->io_re = 1;
    dut->io_regId = 4;
    tick();
    for(int i = 0; i < 32; i++) expected[i] = 0;
    checkData(expected);

    if(!stopFlag) {
        printf("\033[42;32mVerilator: MatTileRegisters Test PASS!\033[0m\n");
    } else {
        printf("\033[41;32mVerilator: MatTileRegisters Test FAILED!\033[0m\n");
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
