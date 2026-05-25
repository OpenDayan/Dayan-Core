#include <stdlib.h>
#include <iostream>
#include <verilated.h>
#include "verilated_fst_c.h"
#include "VMatConvTestTop.h"
#include <vector>
#include <cmath>
#include "fputils.hpp"

#define MAX_SIM_TIME 200000
#define MAX_ERROR 0.1
#define TAIL_SENTINEL (-123.0f)

VerilatedContext *contextp;
VMatConvTestTop *dut;
unsigned long long sim_time = 0;
unsigned int stopFlag = 0;

static const int IAddr = 0x0000 << 3;
static const int KAddr = 0x1000 << 3;
static const int OAddr = 0x2000 << 3;

enum MatCSROffset {
    MAT_CSR_C = 0,
    MAT_CSR_WI = 1,
    MAT_CSR_HI = 2,
    MAT_CSR_WO = 3,
    MAT_CSR_HO = 4,
    MAT_CSR_WK = 5,
    MAT_CSR_HK = 6,
    MAT_CSR_CO = 7,
    MAT_CSR_WI_STRIDE = 8,
    MAT_CSR_HI_STRIDE = 9,
    MAT_CSR_WK_STRIDE = 10,
    MAT_CSR_HK_STRIDE = 11,
    MAT_CSR_CO_STRIDE = 12,
    MAT_CSR_WO_STRIDE = 13,
    MAT_CSR_HO_STRIDE = 14,
    MAT_CSR_I_SWIZZLE_MBS = 15,
    MAT_CSR_K_SWIZZLE_MBS = 16,
    MAT_CSR_O_SWIZZLE_MBS = 17,
    MAT_CSR_DATA_TYPE = 18,
    MAT_CSR_PAD_L = 24,
    MAT_CSR_PAD_U = 25,
    MAT_CSR_DILATION_W = 26,
    MAT_CSR_DILATION_H = 27,
    MAT_CSR_STRIDE_W = 28,
    MAT_CSR_STRIDE_H = 29,
    MAT_CSR_STRIDE_DILATION_W = 30,
    MAT_CSR_STRIDE_DILATION_H = 31,
};

struct CSRWrite {
    int offset;
    int value;
};

struct ConvCase {
    const char* name;
    int ciBytes;
    int wi;
    int hi;
    int wo;
    int ho;
    int wk;
    int hk;
    int co;
    int strideW;
    int strideH;
    int dilationW;
    int dilationH;
    int padL;
    int padU;
    bool zeroStrideDilation;
    bool accumulate;
    float outputInit;
    bool bf16;
};

static const std::vector<ConvCase> convCases = {
    // 基础 1x1：覆盖 Wo/Co 多 tile 和 conv 指令/CSR/地址基础路径。
    {"conv1x1_multi_tile", 32, 32, 1, 32, 1, 1, 1, 16, 1, 1, 1, 1, 0, 0, false, false, 0.0f, false},
    // CiBytes=64：覆盖 Consumer/Producer 的 K tile 多于 1 个的情况。
    {"conv1x1_ci64",       64, 16, 1, 16, 1, 1, 1,  8, 1, 1, 1, 1, 0, 0, false, false, 0.0f, false},
    // 2x2：覆盖 Hk/Wk/CiBytes 映射到 Consumer K 维后的完整卷积累加。
    {"conv2x2_accumulate", 32, 17, 2, 16, 1, 2, 2,  8, 1, 1, 1, 1, 0, 0, false, false, 0.0f, false},
    // Ho=2：覆盖 Ho 外层循环和 HiStride/HoStride。
    {"conv2x2_ho2",        32, 17, 3, 16, 2, 2, 2,  8, 1, 1, 1, 1, 0, 0, false, false, 0.0f, false},
    // Stride=2：覆盖 inputWoStride/inputHoStride。
    {"conv2x2_stride2",    32, 32, 4, 16, 2, 2, 2,  8, 2, 2, 1, 1, 0, 0, false, false, 0.0f, false},
    // Dilation=2：覆盖 StrideDilationW/H CSR。
    {"conv2x2_dilation2",  32, 18, 4, 16, 2, 2, 2,  8, 1, 1, 2, 2, 0, 0, false, false, 0.0f, false},
    // StrideDilation CSR 写 0：覆盖硬件按 stride/dilation 回退计算组合步长。
    {"conv2x2_sd_zero",    32, 18, 4, 16, 2, 2, 2,  8, 1, 1, 2, 2, 0, 0, true,  false, 0.0f, false},
    // 3x3：覆盖更长的 Hk/Wk reduction 计数。
    {"conv3x3_kernel",     32, 18, 3, 16, 1, 3, 3,  8, 1, 1, 1, 1, 0, 0, false, false, 0.0f, false},
    // conv2da：覆盖 O_old + Conv(I, K) 的后处理累加路径。
    {"conv2da_output_acc", 32, 17, 2, 16, 1, 2, 2,  8, 1, 1, 1, 1, 0, 0, false, true, 1.5f, false},
    // BF16：覆盖 DataType=0 下 Ci 逻辑通道数和物理字节数的映射。
    {"conv1x1_bf16",       32, 16, 1, 16, 1, 1, 1,  8, 1, 1, 1, 1, 0, 0, false, false, 0.0f, true},
    // Padding：覆盖 Input Producer 的 zero-fill mask。
    {"conv2x2_pad_left_up", 32, 16, 2, 16, 2, 2, 2,  8, 1, 1, 1, 1, 1, 1, false, false, 0.0f, false},
    // BF16 + Padding：覆盖 BF16 下 padding zero-fill。
    {"conv1x1_bf16_pad_l",  32, 16, 1, 16, 1, 1, 1,  8, 1, 1, 1, 1, 1, 0, false, false, 0.0f, true},
    // Wo tail：覆盖输出宽度不按 cM 对齐时的输入 zero-fill 和输出写回 mask。
    {"conv1x1_wo_tail",     32, 24, 1, 17, 1, 1, 1,  8, 1, 1, 1, 1, 0, 0, false, false, 0.0f, false},
    // Wo tail + conv2da：覆盖尾块旧 O 读取和写回都不访问真实输出范围外。
    {"conv2da_wo_tail",     32, 24, 1, 17, 1, 1, 1,  8, 1, 1, 1, 1, 0, 0, false, true, 1.5f, false},
    // Co tail：覆盖 Kernel Producer 的 N 维 zero-fill 和输出列 byte-enable。
    {"conv1x1_co_tail",      32, 16, 1, 16, 1, 1, 1,  9, 1, 1, 1, 1, 0, 0, false, false, 0.0f, false},
    // Co tail + conv2da：覆盖尾列旧 O 累加路径不会写回无效 Co。
    {"conv2da_co_tail",      32, 16, 1, 16, 1, 1, 1,  9, 1, 1, 1, 1, 0, 0, false, true, 1.5f, false},
    // CiBytes tail：覆盖最后一个 K tile 内部的 byte zero-fill。
    {"conv1x1_ci_tail",      36, 16, 1, 16, 1, 1, 1,  8, 1, 1, 1, 1, 0, 0, false, false, 0.0f, false},
    // BF16 CiBytes tail：覆盖 BF16 逻辑通道数到物理字节数的尾块映射。
    {"conv1x1_bf16_ci_tail", 36, 16, 1, 16, 1, 1, 1,  8, 1, 1, 1, 1, 0, 0, false, false, 0.0f, true},
    // 组合 tail：Wo/Co/CiBytes 同时不对齐。
    {"conv1x1_all_tail",     36, 24, 1, 17, 1, 1, 1,  9, 1, 1, 1, 1, 0, 0, false, false, 0.0f, false},
};

static inline int elemBytes(const ConvCase& c) { return c.bf16 ? 2 : 1; }
static inline int ciCount(const ConvCase& c) { return c.ciBytes / elemBytes(c); }
static inline int paddedCiBytes(const ConvCase& c) { return ((c.ciBytes + 7) / 8) * 8; }
static inline int paddedWo(const ConvCase& c) { return ((c.wo + 15) / 16) * 16; }
static inline int paddedCo(const ConvCase& c) { return ((c.co + 7) / 8) * 8; }
static inline int wiStride(const ConvCase& c) { return paddedCiBytes(c); }
static inline int hiStride(const ConvCase& c) { return c.wi * wiStride(c); }
static inline int coStride(const ConvCase& c) { return paddedCiBytes(c); }
static inline int wkStride(const ConvCase& c) { return c.co * coStride(c); }
static inline int hkStride(const ConvCase& c) { return c.wk * wkStride(c); }
static inline int woStride(const ConvCase& c) { return paddedCo(c) * 4; }
static inline int hoStride(const ConvCase& c) { return paddedWo(c) * woStride(c); }
static inline int inputTensorElements(const ConvCase& c) { return c.hi * c.wi * ciCount(c); }
static inline int kernelTensorElements(const ConvCase& c) { return c.hk * c.wk * c.co * ciCount(c); }
static inline int outputTensorElements(const ConvCase& c) { return c.ho * c.wo * c.co; }
static inline int outputPaddedElements(const ConvCase& c) { return c.ho * paddedWo(c) * paddedCo(c); }
static inline bool isSummaryCase(const ConvCase& c) { return &c == &convCases.back(); }

static inline uint32_t matCSRWriteInst(int offset)
{
    return (0b0000000 << 25) | (offset << 15) | 0xb;
}

static inline uint32_t matConv2DInst()
{
    return (0b0001000 << 25) | (0b010 << 12) | 0xb;
}

static inline uint32_t matConv2DAInst()
{
    return (0b0001000 << 25) | (0b011 << 12) | 0xb;
}

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

extern "C" long long UltraRamWrite(int id, int byte_enable, int waddr, long long wdata)
{
    if (byte_enable == 0xff) [[likely]] {
        uram[id | (waddr << LOG2BANK)] = wdata;
    } else [[unlikely]] {
        uint8_t* dst_addr = (uint8_t*) &(uram[id | (waddr << LOG2BANK)]);
        uint8_t* src_addr = (uint8_t*) &wdata;
        for(int i = 0 ; i < 8 ; i ++) {
            if(byte_enable & 0x1) dst_addr[i] = src_addr[i];
            byte_enable >>= 1;
        }
    }
    return 0;
}

static inline std::vector<CSRWrite> buildCSRWrites(const ConvCase& c)
{
    return {
        {MAT_CSR_C, ciCount(c) - 1},
        {MAT_CSR_WI, c.wi - 1},
        {MAT_CSR_HI, c.hi - 1},
        {MAT_CSR_WO, c.wo - 1},
        {MAT_CSR_HO, c.ho - 1},
        {MAT_CSR_WK, c.wk - 1},
        {MAT_CSR_HK, c.hk - 1},
        {MAT_CSR_CO, c.co - 1},
        {MAT_CSR_WI_STRIDE, wiStride(c)},
        {MAT_CSR_HI_STRIDE, hiStride(c)},
        {MAT_CSR_WK_STRIDE, wkStride(c)},
        {MAT_CSR_HK_STRIDE, hkStride(c)},
        {MAT_CSR_CO_STRIDE, coStride(c)},
        {MAT_CSR_WO_STRIDE, woStride(c)},
        {MAT_CSR_HO_STRIDE, hoStride(c)},
        {MAT_CSR_I_SWIZZLE_MBS, 0},
        {MAT_CSR_K_SWIZZLE_MBS, 0},
        {MAT_CSR_O_SWIZZLE_MBS, 0},
        {MAT_CSR_DATA_TYPE, c.bf16 ? 0 : 1},
        {MAT_CSR_PAD_L, c.padL},
        {MAT_CSR_PAD_U, c.padU},
        {MAT_CSR_DILATION_W, c.dilationW},
        {MAT_CSR_DILATION_H, c.dilationH},
        {MAT_CSR_STRIDE_W, c.strideW},
        {MAT_CSR_STRIDE_H, c.strideH},
        {MAT_CSR_STRIDE_DILATION_W, c.zeroStrideDilation ? 0 : wiStride(c) * c.dilationW},
        {MAT_CSR_STRIDE_DILATION_H, c.zeroStrideDilation ? 0 : hiStride(c) * c.dilationH},
    };
}

static inline int inputAddr(const ConvCase& c, int h, int w, int ci)
{
    return IAddr + h * hiStride(c) + w * wiStride(c) + ci * elemBytes(c);
}

static inline int kernelAddr(const ConvCase& c, int kh, int kw, int co, int ci)
{
    return KAddr + kh * hkStride(c) + kw * wkStride(c) + co * coStride(c) + ci * elemBytes(c);
}

static inline int outputAddr(const ConvCase& c, int h, int w, int co)
{
    return OAddr + h * hoStride(c) + w * woStride(c) + co * 4;
}

static inline float getInputElem(const ConvCase& c, int h, int w, int ci)
{
    if(c.bf16) {
        uint16_t* addr = (uint16_t*)(uramBase + inputAddr(c, h, w, ci));
        return BF16ToFP32(*addr);
    } else {
        uint8_t ret = uramBase[inputAddr(c, h, w, ci)];
        return FP8ToFP32<4>(ret);
    }
}

static inline void setInputElem(const ConvCase& c, int h, int w, int ci, float v)
{
    if(c.bf16) {
        uint16_t* addr = (uint16_t*)(uramBase + inputAddr(c, h, w, ci));
        *addr = FP32ToBF16(v);
    } else {
        uint8_t vfp8 = FP32ToFP8<4>(v);
        uramBase[inputAddr(c, h, w, ci)] = vfp8;
    }
}

static inline float getKernelElem(const ConvCase& c, int kh, int kw, int co, int ci)
{
    if(c.bf16) {
        uint16_t* addr = (uint16_t*)(uramBase + kernelAddr(c, kh, kw, co, ci));
        return BF16ToFP32(*addr);
    } else {
        uint8_t ret = uramBase[kernelAddr(c, kh, kw, co, ci)];
        return FP8ToFP32<4>(ret);
    }
}

static inline void setKernelElem(const ConvCase& c, int kh, int kw, int co, int ci, float v)
{
    if(c.bf16) {
        uint16_t* addr = (uint16_t*)(uramBase + kernelAddr(c, kh, kw, co, ci));
        *addr = FP32ToBF16(v);
    } else {
        uint8_t vfp8 = FP32ToFP8<4>(v);
        uramBase[kernelAddr(c, kh, kw, co, ci)] = vfp8;
    }
}

static inline float getOutputElemFP32(const ConvCase& c, int h, int w, int co)
{
    float* addr = (float*)(outputAddr(c, h, w, co) + uramBase);
    return *addr;
}

static inline void setOutputElemFP32(const ConvCase& c, int h, int w, int co, float v)
{
    float* addr = (float*)(outputAddr(c, h, w, co) + uramBase);
    *addr = v;
}

static inline void printConvCaseSummary(const ConvCase& c)
{
    printf("[%s] DataType: %s, Instruction: %s\n", c.name, c.bf16 ? "BF16" : "FP8",
        c.accumulate ? "conv2da" : "conv2d");
    printf("[%s] Input  I[Hi=%d, Wi=%d, Ci=%d], elements=%d, physical bytes=%d\n",
        c.name, c.hi, c.wi, ciCount(c), inputTensorElements(c), c.hi * c.wi * paddedCiBytes(c));
    printf("[%s] Kernel K[Hk=%d, Wk=%d, Ci=%d, Co=%d], elements=%d, physical bytes=%d\n",
        c.name, c.hk, c.wk, ciCount(c), c.co, kernelTensorElements(c),
        c.hk * c.wk * c.co * coStride(c));
    printf("[%s] Output O[Ho=%d, Wo=%d, Co=%d], elements=%d, padded O[Ho=%d, Wo=%d, Co=%d], padded elements=%d\n",
        c.name, c.ho, c.wo, c.co, outputTensorElements(c),
        c.ho, paddedWo(c), paddedCo(c), outputPaddedElements(c));
    printf("[%s] Kernel: Hk=%d, Wk=%d; StrideH=%d, StrideW=%d; PadH=%d, PadW=%d; DilationH=%d, DilationW=%d\n",
        c.name, c.hk, c.wk, c.strideH, c.strideW, c.padU, c.padL, c.dilationH, c.dilationW);
    printf("[%s] Stride bytes: WiStride=%d, HiStride=%d, CoStride=%d, WkStride=%d, HkStride=%d, WoStride=%d, HoStride=%d\n",
        c.name, wiStride(c), hiStride(c), coStride(c), wkStride(c), hkStride(c), woStride(c), hoStride(c));
}

static inline void printOutputTensor(const ConvCase& c)
{
    printf("[%s] Full valid output tensor O[Ho=%d, Wo=%d, Co=%d]:\n", c.name, c.ho, c.wo, c.co);
    for(int h = 0; h < c.ho; h++) {
        for(int w = 0; w < c.wo; w++) {
            printf("[%s] O[h=%d, w=%d, :] =", c.name, h, w);
            for(int co = 0; co < c.co; co++) {
                printf(" %f", getOutputElemFP32(c, h, w, co));
            }
            printf("\n");
        }
    }
}

static inline float inputPattern(const ConvCase& c, int h, int w, int ci)
{
    if(ci == 0) return (float)(1 + (h & 1));
    if(ci == 4) return (float)(1 + (w & 3));
    if(ci == 8) return 0.5f;
    if(ciCount(c) > 16 && ci == 16) return (float)(1 + ((h + w) & 1));
    if(ciCount(c) > 17 && ci == 17) return 0.5f;
    if(ciCount(c) > 32 && ci == 32) return (float)(1 + ((h + w) & 1));
    if(ciCount(c) > 35 && ci == 35) return 0.5f;
    return 0.0f;
}

static inline float kernelPattern(const ConvCase& c, int kh, int kw, int co, int ci)
{
    if(ci == 0) return (float)(1 + kh + kw);
    if(ci == 4) return (float)(1 + (co % 3));
    if(ci == 8) return 0.5f;
    if(ciCount(c) > 16 && ci == 16) return (float)(1 + (co & 1));
    if(ciCount(c) > 17 && ci == 17) return 0.5f;
    if(ciCount(c) > 32 && ci == 32) return (float)(1 + (co & 1));
    if(ciCount(c) > 35 && ci == 35) return 0.5f;
    return 0.0f;
}

static inline void initConvTensor(const ConvCase& c)
{
    for(int h = 0; h < c.hi; h++) {
        for(int w = 0; w < c.wi; w++) {
            for(int ci = 0; ci < ciCount(c); ci++) {
                setInputElem(c, h, w, ci, inputPattern(c, h, w, ci));
            }
        }
    }
    for(int kh = 0; kh < c.hk; kh++) {
        for(int kw = 0; kw < c.wk; kw++) {
            for(int co = 0; co < c.co; co++) {
                for(int ci = 0; ci < ciCount(c); ci++) {
                    setKernelElem(c, kh, kw, co, ci, kernelPattern(c, kh, kw, co, ci));
                }
            }
        }
    }
    for(int h = 0; h < c.ho; h++) {
        for(int w = 0; w < paddedWo(c); w++) {
            for(int co = 0; co < paddedCo(c); co++) {
                setOutputElemFP32(c, h, w, co, w < c.wo && co < c.co ? c.outputInit : TAIL_SENTINEL);
            }
        }
    }
}

static inline float calcConvRef(const ConvCase& c, int ho, int wo, int co)
{
    float acc = 0.0f;
    for(int kh = 0; kh < c.hk; kh++) {
        for(int kw = 0; kw < c.wk; kw++) {
            int ih = ho * c.strideH + kh * c.dilationH - c.padU;
            int iw = wo * c.strideW + kw * c.dilationW - c.padL;
            if(ih < 0 || ih >= c.hi || iw < 0 || iw >= c.wi) continue;
            for(int ci = 0; ci < ciCount(c); ci++) {
                acc += getInputElem(c, ih, iw, ci) * getKernelElem(c, kh, kw, co, ci);
            }
        }
    }
    return acc;
}

static int handleOCompare(const ConvCase& c, int h, int w, int co, float ref)
{
    float dutValue = getOutputElemFP32(c, h, w, co);
    float refAbs = fabs(ref);
    float diffAbs = fabs(ref - dutValue);
    bool pass;
    if(refAbs <= MAX_ERROR) {
        pass = diffAbs <= MAX_ERROR;
    } else {
        pass = diffAbs / refAbs <= MAX_ERROR;
    }
    if(!pass) {
        fprintf(stderr, "Case %s, URAM Addr: 0x%x\n", c.name, outputAddr(c, h, w, co));
        fprintf(stderr, "Error in H%d W%d Co%d, ref: %f, dut: %f\n", h, w, co, ref, dutValue);
        return 1;
    }
    return 0;
}

static int checkConvTensor(const ConvCase& c)
{
    int caseErrorCount = 0;
    for(int h = 0; h < c.ho; h++) {
        for(int w = 0; w < c.wo; w++) {
            for(int co = 0; co < c.co; co++) {
                float ref = calcConvRef(c, h, w, co) + (c.accumulate ? c.outputInit : 0.0f);
                caseErrorCount += handleOCompare(c, h, w, co, ref);
            }
        }
    }
    for(int h = 0; h < c.ho; h++) {
        for(int w = 0; w < c.wo; w++) {
            for(int co = c.co; co < paddedCo(c); co++) {
                caseErrorCount += handleOCompare(c, h, w, co, TAIL_SENTINEL);
            }
        }
        for(int w = c.wo; w < paddedWo(c); w++) {
            for(int co = 0; co < c.co; co++) {
                caseErrorCount += handleOCompare(c, h, w, co, TAIL_SENTINEL);
            }
            for(int co = c.co; co < paddedCo(c); co++) {
                caseErrorCount += handleOCompare(c, h, w, co, TAIL_SENTINEL);
            }
        }
    }
    printf("[%s] O[0, 0, 0] = %f\n", c.name, getOutputElemFP32(c, 0, 0, 0));
    printf("[%s] O[0, 0, 1] = %f\n", c.name, getOutputElemFP32(c, 0, 0, 1));
    printf("[%s] O[0, 1, 0] = %f\n", c.name, getOutputElemFP32(c, 0, 1, 0));
    printf("[%s] O[0, 1, 1] = %f\n", c.name, getOutputElemFP32(c, 0, 1, 1));
    if(isSummaryCase(c)) {
        printOutputTensor(c);
    }
    return caseErrorCount;
}

static size_t caseIdx = 0;
static std::vector<CSRWrite> csrWrites;
static size_t csrWriteIdx = 0;
static bool convIssued = false;
static bool allDone = false;
static int finishCount = 0;
static int caseStartTime = 0;
static int totalErrorCount = 0;

static inline void beginCase(size_t idx)
{
    const ConvCase& c = convCases[idx];
    csrWrites = buildCSRWrites(c);
    csrWriteIdx = 0;
    convIssued = false;
    caseStartTime = 0;
    initConvTensor(c);
    printf("Start Conv Case %zu/%zu: %s\n", idx + 1, convCases.size(), c.name);
    if(isSummaryCase(c)) {
        printConvCaseSummary(c);
    }
}

static inline void driveInstReq()
{
    dut->instReq_valid = 0;
    dut->instReq_bits_instWord = 0;
    dut->instReq_bits_rs1Data = 0;
    dut->instReq_bits_rs2Data = 0;
    dut->instReq_bits_rdData = 0;

    if(allDone) return;

    if(csrWriteIdx < csrWrites.size()) {
        dut->instReq_valid = 1;
        dut->instReq_bits_rdData = csrWrites[csrWriteIdx].value;
        dut->instReq_bits_instWord = matCSRWriteInst(csrWrites[csrWriteIdx].offset);
    } else if(!convIssued) {
        dut->instReq_valid = 1;
        dut->instReq_bits_rs1Data = IAddr;
        dut->instReq_bits_rs2Data = KAddr;
        dut->instReq_bits_rdData = OAddr;
        dut->instReq_bits_instWord = convCases[caseIdx].accumulate ? matConv2DAInst() : matConv2DInst();
    }
}

static inline void mainLoop()
{
    dut->instResp_ready = 1;
    dut->sbComp_ready = 1;

    if(dut->instReq_valid && dut->instReq_ready) {
        if(csrWriteIdx < csrWrites.size()) {
            csrWriteIdx++;
        } else if(!convIssued) {
            convIssued = true;
            caseStartTime = sim_time;
        }
    }

    if(dut->finish) {
        finishCount++;
        const ConvCase& c = convCases[caseIdx];
        int caseFinishTime = sim_time;
        printf("Get Finish Pulse:%d\n", finishCount);
        printf("[%s] Calculation Latency: %d Cycles\n", c.name, caseFinishTime - caseStartTime);
        totalErrorCount += checkConvTensor(c);
        if(totalErrorCount != 0) {
            stopFlag = 2;
        } else if(caseIdx + 1 == convCases.size()) {
            allDone = true;
            stopFlag = 2;
        } else {
            caseIdx++;
            beginCase(caseIdx);
        }
    }

    driveInstReq();
}

int main(int argc, char **argv)
{
    contextp = new VerilatedContext;
    contextp->commandArgs(argc, argv);
    dut = new VMatConvTestTop;
    Verilated::traceEverOn(true);
    VerilatedFstC *m_trace = new VerilatedFstC;
    dut->trace(m_trace, 99);
    m_trace->open("wave/MatConvTestTop.fst");
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
    beginCase(0);
    driveInstReq();
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
    if(allDone && totalErrorCount == 0) {
        printf("\033[42;32mVerilator: MatConvTestTop Test PASS!\033[0m\n");
    } else {
        if(!allDone) {
            fprintf(stderr, "MatConvTestTop timeout or stopped before all cases finished.\n");
        }
        printf("\033[41;32mVerilator: MatConvTestTop Test FAILED!\033[0m\n");
    }
    m_trace->close();
    delete dut;
    exit(EXIT_SUCCESS);
}

void vl_fatal(const char* filename, int line, const char* func, const char* msg) {
    std::cerr << "Fatal error occurred at " << filename << ":" << line << " (function: " << func << ") - " << msg << std::endl;
    totalErrorCount++;
    stopFlag = 5;
}

void vl_stop(const char* filename, int line, const char* func) {
    std::cerr << "stop occurred at " << filename << ":" << line << " (function: " << func << ")" << std::endl;
    totalErrorCount++;
    stopFlag = 5;
}
