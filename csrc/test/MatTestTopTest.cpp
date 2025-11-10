#include <stdlib.h>
#include <iostream>
#include <verilated.h>
#include "verilated_fst_c.h"
#include "VMatTestTop.h"
#include <vector>
#include <queue>
#include "fputils.hpp"
#define MAX_SIM_TIME 10000
#define MAX_ERROR 0.1
VerilatedContext *contextp;
VMatTestTop *dut;
unsigned long long sim_time = 0;
unsigned int stopFlag = 0;

#define ALIGNBYTE 32

// #define URAM_SYNC_WRITE

// Global Settings
int rndInt = 0;
int enableTrace = 1;
// int M = 16;
// int N = 16;  // Aligned to  8
// int rawN = 16;
// int K = 32; // Aligned to 32
// int rawK = 32;
// int AAddr =  0x00 << 3;
// int BAddr =  0x80 << 3;
// int CAddr = 0x100 << 3;
int M = 41;
int N = 256;  // Aligned to  8
int rawN = 256;
int K = 128; // Aligned to 32
int rawK = 128;
int AAddr = 0x0000 << 3;
int BAddr = 0x2000 << 3;
int CAddr = 0x4000 << 3;

int sM = 2, sN = 2, sK = 3;
int persistentMode = 0;

// Random Range
int randomRange = 480;

// Bank Count
// 1: 2, 2: 4, 3: 8, 4: 16, 5: 32
#define LOG2BANK 5
#define URAMSIZE ((1 << LOG2BANK) * 32 * 1024ull)
long int uram[URAMSIZE / 8]; // 1024KBytes URAM
static char* uramBase = (char*)&uram;

extern "C" long long UltraRamRead(int id, int raddr)
{
    long long rtValue = uram[id | (raddr << LOG2BANK)];
    return rtValue;
}

extern "C" long long UltraRamWrite(int id, int waddr, long int wdata)
{
    uram[id | (waddr << LOG2BANK)] = wdata;
    return 0;
}

static inline float getMatrixElemFP8(int base, int major, int minor)
{
    uint8_t ret;
    int stride = base == CAddr ? (N * 4) : K;
    ret = uramBase[major * stride + minor + base];
    return FP8ToFP32<4>(ret);
}

static inline void setMatrixElemFP8(int base, int major, int minor, float v)
{
    int stride = base == CAddr ? (N * 4) : K;
    uint8_t vfp8 = FP32ToFP8<4>(v);
    uramBase[major * stride + minor + base] = vfp8;
}

static inline float getMatrixElemFP32(int base, int major, int minor)
{
    int stride = base == CAddr ? (N * 4) : K;
    float* addr = (float*)(major * stride + minor * 4 + base + uramBase);
    return *addr;
}

static inline void setMatrixElemFP32(int base, int major, int minor, float v)
{
    int stride = base == CAddr ? (N * 4) : K;
    float* addr = (float*)(major * stride + minor * 4 + base + uramBase);
    *addr = v;
}

static inline void initMatrix()
{
    if(rndInt) {
        // printf("Start to random init matrix M%dN%dK%d.\n", M, N, K);
        printf("\"FP8M%dN%dK%d\": {\n", M, rawN, rawK);
        // A Random Init
        for(int m = 0; m < M; m ++) {
            for(int k = 0; k < K; k++) {
                setMatrixElemFP8(AAddr, m, k, GenRandomFloat(randomRange));
            }
        }
        // B Random Init
        for(int n = 0; n < N; n ++) {
            for(int k = 0; k < K; k++) {
                setMatrixElemFP8(BAddr, n, k, GenRandomFloat(randomRange));
            }
        }
    } else {
        setMatrixElemFP8(AAddr, 0, 0, 1.0f);
        setMatrixElemFP8(AAddr, 0, 1, 2.0f);
        setMatrixElemFP8(AAddr, 0, 2, 3.0f);
        setMatrixElemFP8(AAddr, 5, 0, 4.0f);
        setMatrixElemFP8(AAddr, 5, 1, 5.0f);
        setMatrixElemFP8(AAddr, 5, 2, 6.0f);
        setMatrixElemFP8(BAddr, 0, 0, 1.0f);
        setMatrixElemFP8(BAddr, 0, 1, 2.0f);
        setMatrixElemFP8(BAddr, 0, 2, 3.0f);
        setMatrixElemFP8(BAddr, 3, 0, 4.0f);
        setMatrixElemFP8(BAddr, 3, 1, 5.0f);
        setMatrixElemFP8(BAddr, 3, 2, 6.0f);
    }
}

static int finishCount = 0;
static int startTime = 0;
static int finishTime = 0;
static long int totalFinishGap = 0;

static int handleCCompare(int m, int n, float ref) {
    float dut = getMatrixElemFP32(CAddr, m, n);
    float refAbs = fabs(ref);
    float diffAbs = fabs(ref - dut);
    bool pass;
    if(refAbs <= MAX_ERROR) {
        pass = diffAbs <= MAX_ERROR;
    } else {
        pass = diffAbs / refAbs <= MAX_ERROR;
    }
    if(!pass) {
        fprintf(stderr, "URAM Addr: 0x%x\n", (CAddr + (m * N + n) * 4) >> 3);
        fprintf(stderr, "Error in M%d N%d, ref: %f, dut: %f\n", m, n, ref, dut);
    }
    return 0;
}

static inline void checkMatrix()
{
    if(rndInt) {
        printf("\"Latency\": \"%d\",\n", finishTime - startTime);
        CalcFP8MatMul((uint8_t*)uramBase + AAddr, (uint8_t*)uramBase + BAddr, handleCCompare, M, N, K);
    } else {
        printf("Get Finish Pulse:%d\n", finishCount);
        printf("Calculation Latency: %d Cycles\n", finishTime - startTime);
        printf("A[0, 0] = %f\n", getMatrixElemFP8 (0xA000, 0, 0));
        printf("A[0, 1] = %f\n", getMatrixElemFP8 (0xA000, 0, 1));
        printf("A[0, 2] = %f\n", getMatrixElemFP8 (0xA000, 0, 2));
        printf("A[5, 0] = %f\n", getMatrixElemFP8 (0xA000, 5, 0));
        printf("A[5, 1] = %f\n", getMatrixElemFP8 (0xA000, 5, 1));
        printf("A[5, 2] = %f\n", getMatrixElemFP8 (0xA000, 5, 2));
        printf("B[3, 0] = %f\n", getMatrixElemFP8 (0xB000, 3, 0));
        printf("B[3, 1] = %f\n", getMatrixElemFP8 (0xB000, 3, 1));
        printf("B[3, 2] = %f\n", getMatrixElemFP8 (0xB000, 3, 2));
        printf("C[0, 0] = %f\n", getMatrixElemFP32(0xC000, 0, 0));
        printf("C[5, 0] = %f\n", getMatrixElemFP32(0xC000, 5, 0));
        printf("C[0, 3] = %f\n", getMatrixElemFP32(0xC000, 0, 3));
        printf("C[5, 3] = %f\n", getMatrixElemFP32(0xC000, 5, 3));
    }
}


static inline void mainLoop()
{
    static int lastFinishTime = 0;
    dut->matInst_valid = (startTime == 0) || (finishTime != 0);
    if(sim_time == 0) {
        // problemM
        dut->matInst_bits_rdData = M;
        dut->matInst_bits_instWord = (0b0000000 << 25) | (0 << 15);
    } else if(sim_time == 1) {
        // problemN
        dut->matInst_bits_rdData = N;
        dut->matInst_bits_instWord = (0b0000000 << 25) | (1 << 15);
    } else if(sim_time == 2) {
        // problemK
        dut->matInst_bits_rdData = K;
        dut->matInst_bits_instWord = (0b0000000 << 25) | (2 << 15);
    } else if(sim_time == 3) {
        // strideA
        dut->matInst_bits_rdData = K;
        dut->matInst_bits_instWord = (0b0000000 << 25) | (3 << 15);
    } else if(sim_time == 4) {
        // strideB
        dut->matInst_bits_rdData = K;
        dut->matInst_bits_instWord = (0b0000000 << 25) | (4 << 15);
    } else if(sim_time == 5) {
        // strideC
        dut->matInst_bits_rdData = N * 4;
        dut->matInst_bits_instWord = (0b0000000 << 25) | (5 << 15);
    } else if(sim_time == 6) {
        // sM
        dut->matInst_bits_rdData = sM;
        dut->matInst_bits_instWord = (0b0000000 << 25) | (6 << 15);
    } else if(sim_time == 7) {
        // sN
        dut->matInst_bits_rdData = sN;
        dut->matInst_bits_instWord = (0b0000000 << 25) | (7 << 15);
    } else if(sim_time == 8) {
        // sK
        dut->matInst_bits_rdData = sK;
        dut->matInst_bits_instWord = (0b0000000 << 25) | (8 << 15);
    } else if(sim_time == 9) {
        // persistentMode
        dut->matInst_bits_rdData = persistentMode;
        dut->matInst_bits_instWord = (0b0000000 << 25) | (9 << 15);
    } else {
        dut->matInst_bits_rs1Data = AAddr;
        dut->matInst_bits_rs2Data = BAddr;
        dut->matInst_bits_rdData  = CAddr;
        dut->matInst_bits_instWord = (0b0001000 << 25) | (5 << 15);
        if(startTime == 0) startTime = sim_time;
    }
    if(dut->finish) {
        finishCount ++;
        if(finishTime == 0) {
            finishTime = sim_time;
            checkMatrix();
        } else {
            // 测量吞吐
            if(lastFinishTime != 0) {
                int curGap = sim_time - lastFinishTime;
                totalFinishGap += curGap;
                if(!rndInt) {
                    if(finishCount % 100 == 2){
                        printf("%d Cycles for Current Round.\n", curGap);
                        printf("%f Cycles/Round\n", (double)totalFinishGap/(double)(finishCount - 2));
                    }
                } else {
                    if(finishCount == 10) {
                        stopFlag = 2;
                        double cycle = (double)totalFinishGap/(double)(finishCount - 2);
                        double FloatOp = M * rawN * rawK * 2;
                        printf("\"Throughput\": \"%f\",\n", cycle);
                        printf("\"FloatOpPerCycle\": \"%f\"\n}", FloatOp / cycle);
                    }
                }
            }
            lastFinishTime = sim_time;
        }
    }
}
int main(int argc, char **argv)
{
    contextp = new VerilatedContext;
    if(argc >= 4) {
        // [exec] M N K
        M = strtol(argv[1], NULL, 10);
        rawN = strtol(argv[2], NULL, 10);
        rawK = strtol(argv[3], NULL, 10);
        N = (rawN + (ALIGNBYTE/4) - 1) / (ALIGNBYTE/4) * (ALIGNBYTE/4);
        K = (rawK + ALIGNBYTE - 1) / ALIGNBYTE * ALIGNBYTE;
        AAddr = 0x0;
        BAddr = AAddr + M * K;
        CAddr = BAddr + N * K;
        long totalMem = CAddr + M * N * 4;
        if(totalMem > URAMSIZE) {
            printf("Error: used Storage %ld > URAMSIZE %lld.\n", totalMem, URAMSIZE);
            return -1;
        }
        rndInt = 1;
        enableTrace = 0;
        if(argc >= 5) {
            sM = strtol(argv[4], NULL, 10);
            if(sM == -1) {
                printf("Enable trace.\n");
                printf("Aligned M: %d, N: %d, K: %d\n", M, N, K);
                sM = 2;
                enableTrace = 1;
            }
        }
        if(argc >= 6) sN = strtol(argv[5], NULL, 10);
        if(argc >= 7) sK = strtol(argv[6], NULL, 10);
        if(argc >= 8) persistentMode = strtol(argv[7], NULL, 10);
    } else {
        contextp->commandArgs(argc, argv);
    }
    dut = new VMatTestTop;
    Verilated::traceEverOn(true);
    VerilatedFstC *m_trace = new VerilatedFstC;
    if(enableTrace) dut->trace(m_trace, 99);
    if(enableTrace) m_trace->open("wave/MatTestTop.fst");
    dut->clock = 0;
    dut->reset = 1;
    dut->eval();
    if(enableTrace) m_trace->dump(contextp->time());
    contextp->timeInc(1);
    for (int i = 0; i < 20; i++)
    {
        dut->clock ^= 1;
        dut->eval();
        if(enableTrace) m_trace->dump(contextp->time());
        contextp->timeInc(1);
    }
    dut->reset = 0;
    initMatrix();
    while (sim_time < MAX_SIM_TIME || !enableTrace)
    {
        dut->clock ^= 1;
        dut->eval();
        mainLoop();
        if(enableTrace) m_trace->dump(contextp->time());
        contextp->timeInc(1);
        dut->clock ^= 1;
        dut->eval();
        if(enableTrace) m_trace->dump(contextp->time());
        contextp->timeInc(1);
        sim_time++;
        if(stopFlag != 0) {
            if((--stopFlag) == 1) break;
        }
    }
    if(!rndInt){
        checkMatrix();
        if(!stopFlag) {
            printf("\033[42;32mVerilator: MatTestTop Test PASS!\033[0m\n");
        } else {
            printf("\033[41;32mVerilator: MatTestTop Test FAILED!\033[0m\n");
        }
    }
    if(enableTrace) m_trace->close();
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
