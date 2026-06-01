#include <stdlib.h>
#include <iostream>
#include <vector>
#include <verilated.h>
#include "verilated_fst_c.h"
#include "VNBBasicXBar.h"

#define MAX_SIM_TIME 500

VerilatedContext *contextp;
VNBBasicXBar *dut;
VerilatedFstC *m_trace;
unsigned long long sim_time = 0;
unsigned int stopFlag = 0;

struct Stimulus {
    int src;
    unsigned int flit;
};

struct Expected {
    int dst;
    int src;
    unsigned int flit;
    bool seen;
};

static inline void fail(const char *msg)
{
    std::cerr << "NBBasicXBarTest: " << msg << std::endl;
    if(!stopFlag) stopFlag = 5;
}

static inline void failUnexpected(int dst, unsigned int flit, unsigned int src)
{
    std::cerr << "NBBasicXBarTest: unexpected output dst=" << dst
              << " src=" << src
              << " flit=0x" << std::hex << flit << std::dec << std::endl;
    if(!stopFlag) stopFlag = 5;
}

#define CLEAR_IN(ID) do { \
    dut->io_inFlit_##ID##_valid = 0; \
    dut->io_inFlit_##ID##_bits = 0; \
} while(0)

#define READY_OUT(ID) do { \
    dut->io_outFlit_##ID##_ready = 1; \
} while(0)

#define CAPTURE_OUT(ID, EXPECTED) do { \
    if(dut->io_outFlit_##ID##_valid) { \
        recordOutput((ID), dut->io_outFlit_##ID##_bits_flit, \
                     dut->io_outFlit_##ID##_bits_src, (EXPECTED)); \
    } \
} while(0)

static inline int routeOf(unsigned int flit)
{
    return flit & 0x1f;
}

static inline void clearInputs()
{
    CLEAR_IN(0); CLEAR_IN(1); CLEAR_IN(2); CLEAR_IN(3);
    CLEAR_IN(4); CLEAR_IN(5); CLEAR_IN(6); CLEAR_IN(7);
    CLEAR_IN(8); CLEAR_IN(9); CLEAR_IN(10); CLEAR_IN(11);
    CLEAR_IN(12); CLEAR_IN(13); CLEAR_IN(14); CLEAR_IN(15);
    CLEAR_IN(16); CLEAR_IN(17); CLEAR_IN(18); CLEAR_IN(19);
    CLEAR_IN(20); CLEAR_IN(21); CLEAR_IN(22); CLEAR_IN(23);
    CLEAR_IN(24); CLEAR_IN(25); CLEAR_IN(26); CLEAR_IN(27);
    CLEAR_IN(28); CLEAR_IN(29); CLEAR_IN(30); CLEAR_IN(31);
    CLEAR_IN(32); CLEAR_IN(33); CLEAR_IN(34); CLEAR_IN(35);
    CLEAR_IN(36); CLEAR_IN(37); CLEAR_IN(38); CLEAR_IN(39);
    CLEAR_IN(40); CLEAR_IN(41); CLEAR_IN(42); CLEAR_IN(43);
    CLEAR_IN(44); CLEAR_IN(45); CLEAR_IN(46); CLEAR_IN(47);
    CLEAR_IN(48); CLEAR_IN(49); CLEAR_IN(50); CLEAR_IN(51);
    CLEAR_IN(52); CLEAR_IN(53); CLEAR_IN(54); CLEAR_IN(55);
    CLEAR_IN(56); CLEAR_IN(57); CLEAR_IN(58); CLEAR_IN(59);
    CLEAR_IN(60); CLEAR_IN(61); CLEAR_IN(62); CLEAR_IN(63);
    CLEAR_IN(64); CLEAR_IN(65); CLEAR_IN(66); CLEAR_IN(67);
    CLEAR_IN(68); CLEAR_IN(69); CLEAR_IN(70); CLEAR_IN(71);
    CLEAR_IN(72); CLEAR_IN(73); CLEAR_IN(74); CLEAR_IN(75);
    CLEAR_IN(76); CLEAR_IN(77); CLEAR_IN(78); CLEAR_IN(79);
    CLEAR_IN(80); CLEAR_IN(81); CLEAR_IN(82); CLEAR_IN(83);
    CLEAR_IN(84); CLEAR_IN(85); CLEAR_IN(86); CLEAR_IN(87);
    CLEAR_IN(88); CLEAR_IN(89); CLEAR_IN(90); CLEAR_IN(91);
    CLEAR_IN(92); CLEAR_IN(93); CLEAR_IN(94); CLEAR_IN(95);
}

static inline void readyOutputs()
{
    READY_OUT(0); READY_OUT(1); READY_OUT(2); READY_OUT(3);
    READY_OUT(4); READY_OUT(5); READY_OUT(6); READY_OUT(7);
    READY_OUT(8); READY_OUT(9); READY_OUT(10); READY_OUT(11);
    READY_OUT(12); READY_OUT(13); READY_OUT(14); READY_OUT(15);
    READY_OUT(16); READY_OUT(17); READY_OUT(18); READY_OUT(19);
    READY_OUT(20); READY_OUT(21); READY_OUT(22); READY_OUT(23);
    READY_OUT(24); READY_OUT(25); READY_OUT(26); READY_OUT(27);
    READY_OUT(28); READY_OUT(29); READY_OUT(30); READY_OUT(31);
}

static inline void setDefaults()
{
    clearInputs();
    readyOutputs();
}

static inline void driveInput(int src, unsigned int flit)
{
    switch(src) {
    case 0: dut->io_inFlit_0_valid = 1; dut->io_inFlit_0_bits = flit; break;
    case 1: dut->io_inFlit_1_valid = 1; dut->io_inFlit_1_bits = flit; break;
    case 2: dut->io_inFlit_2_valid = 1; dut->io_inFlit_2_bits = flit; break;
    case 3: dut->io_inFlit_3_valid = 1; dut->io_inFlit_3_bits = flit; break;
    case 4: dut->io_inFlit_4_valid = 1; dut->io_inFlit_4_bits = flit; break;
    case 5: dut->io_inFlit_5_valid = 1; dut->io_inFlit_5_bits = flit; break;
    case 6: dut->io_inFlit_6_valid = 1; dut->io_inFlit_6_bits = flit; break;
    case 7: dut->io_inFlit_7_valid = 1; dut->io_inFlit_7_bits = flit; break;
    default: fail("driveInput only supports the exercised source range");
    }
}

static inline bool inputReady(int src)
{
    switch(src) {
    case 0: return dut->io_inFlit_0_ready;
    case 1: return dut->io_inFlit_1_ready;
    case 2: return dut->io_inFlit_2_ready;
    case 3: return dut->io_inFlit_3_ready;
    case 4: return dut->io_inFlit_4_ready;
    case 5: return dut->io_inFlit_5_ready;
    case 6: return dut->io_inFlit_6_ready;
    case 7: return dut->io_inFlit_7_ready;
    default: return false;
    }
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

static inline void recordOutput(int dst, unsigned int flit, unsigned int src,
                                std::vector<Expected> &expected)
{
    for(auto &exp: expected) {
        if(!exp.seen && exp.dst == dst && exp.src == static_cast<int>(src) && exp.flit == flit) {
            exp.seen = true;
            return;
        }
    }
    failUnexpected(dst, flit, src);
}

static inline void collectOutputs(std::vector<Expected> &expected)
{
    CAPTURE_OUT(0, expected); CAPTURE_OUT(1, expected);
    CAPTURE_OUT(2, expected); CAPTURE_OUT(3, expected);
    CAPTURE_OUT(4, expected); CAPTURE_OUT(5, expected);
    CAPTURE_OUT(6, expected); CAPTURE_OUT(7, expected);
    CAPTURE_OUT(8, expected); CAPTURE_OUT(9, expected);
    CAPTURE_OUT(10, expected); CAPTURE_OUT(11, expected);
    CAPTURE_OUT(12, expected); CAPTURE_OUT(13, expected);
    CAPTURE_OUT(14, expected); CAPTURE_OUT(15, expected);
    CAPTURE_OUT(16, expected); CAPTURE_OUT(17, expected);
    CAPTURE_OUT(18, expected); CAPTURE_OUT(19, expected);
    CAPTURE_OUT(20, expected); CAPTURE_OUT(21, expected);
    CAPTURE_OUT(22, expected); CAPTURE_OUT(23, expected);
    CAPTURE_OUT(24, expected); CAPTURE_OUT(25, expected);
    CAPTURE_OUT(26, expected); CAPTURE_OUT(27, expected);
    CAPTURE_OUT(28, expected); CAPTURE_OUT(29, expected);
    CAPTURE_OUT(30, expected); CAPTURE_OUT(31, expected);
}

static inline bool allSeen(const std::vector<Expected> &expected)
{
    for(const auto &exp: expected) {
        if(!exp.seen) return false;
    }
    return true;
}

static inline void reportMissing(const std::vector<Expected> &expected)
{
    for(const auto &exp: expected) {
        if(!exp.seen) {
            std::cerr << "NBBasicXBarTest: missing output dst=" << exp.dst
                      << " src=" << exp.src
                      << " flit=0x" << std::hex << exp.flit << std::dec << std::endl;
        }
    }
    if(!stopFlag) stopFlag = 5;
}

static inline void issueBatch(const std::vector<Stimulus> &inputs,
                              std::vector<Expected> &expected)
{
    setDefaults();
    for(const auto &in: inputs) {
        driveInput(in.src, in.flit);
    }
    dut->eval();
    for(const auto &in: inputs) {
        if(!inputReady(in.src)) fail("selected input should be ready before issuing");
        expected.push_back(Expected{routeOf(in.flit), in.src, in.flit, false});
    }
    tick();
}

static inline void waitForExpected(std::vector<Expected> &expected, int maxCycles)
{
    for(int i = 0; i < maxCycles && !stopFlag; i++) {
        setDefaults();
        dut->clock = 0;
        dut->eval();
        collectOutputs(expected);
        m_trace->dump(contextp->time());
        contextp->timeInc(1);

        dut->clock = 1;
        dut->eval();
        m_trace->dump(contextp->time());
        contextp->timeInc(1);
        sim_time++;

        if(allSeen(expected)) return;
    }
    if(!allSeen(expected)) reportMissing(expected);
}

int main(int argc, char **argv)
{
    contextp = new VerilatedContext;
    contextp->commandArgs(argc, argv);
    dut = new VNBBasicXBar;
    Verilated::traceEverOn(true);
    m_trace = new VerilatedFstC;
    dut->trace(m_trace, 99);
    m_trace->open("wave/NBBasicXBar.fst");

    dut->clock = 0;
    dut->reset = 1;
    setDefaults();
    for(int i = 0; i < 10; i++) tick();
    dut->reset = 0;
    tick();

    std::vector<Expected> expected;

    issueBatch({{0, 0x10000005}}, expected);
    waitForExpected(expected, 20);

    expected.clear();
    issueBatch({
        {1, 0x21000003},
        {2, 0x22000007},
        {3, 0x2300000c},
        {4, 0x2400001f},
    }, expected);
    waitForExpected(expected, 30);

    expected.clear();
    issueBatch({
        {5, 0x35000009},
        {6, 0x36000009},
        {7, 0x37000009},
    }, expected);
    waitForExpected(expected, 40);

    if(!stopFlag) {
        printf("\033[42;32mVerilator: NBBasicXBar Test PASS!\033[0m\n");
    } else {
        printf("\033[41;32mVerilator: NBBasicXBar Test FAILED!\033[0m\n");
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
