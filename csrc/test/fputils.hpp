#pragma once
#include <vector>
#include <math.h>
#include <stdint.h>

static inline float GenRandomFloat(float scale) {
    return (double)scale * (rand() - RAND_MAX/2) / (double)RAND_MAX;
}

static inline std::vector<float> GenRandomFloats(int count, float scale) {
    std::vector<float> r(count);
    for(int i = 0; i < count; i ++) {
        r[i] = GenRandomFloat(scale);
    }
    return r;
}

static inline std::vector<int> GenRandomInt(int count, int scale) {
    std::vector<int> r(count);
    for(int i = 0; i < count ; i ++) {
        r[i] = (long long)scale * (rand() - RAND_MAX / 2) / (long long) RAND_MAX;
    }
    return r;
}

static inline float ScaleToFP32(int scale) {
    return exp2f((float)scale);
}

static inline uint8_t getUpper(uint16_t a) {
    return (a >> 8) & 0xff;
}

static inline uint8_t getLower(uint16_t a) {
    return a & 0xff;
}

static inline uint16_t FP32ToBF16(float a) {
    uint32_t aI = *(int*)&a;
    return (aI >> 16) & 0xffff;
}

static inline float BF16ToFP32(uint16_t a) {
    uint32_t e = a << 16;
    float aF = *(float*)&e;
    return aF;
}

static inline float BF16Clip(float a) {
    return BF16ToFP32(FP32ToBF16(a));
}

union fp32_int32 {
    float f;
    uint32_t bits;
};

static inline uint32_t FP32ToSExp(float a) {
    fp32_int32 f;
    f.f = a;
    int raw_exp = (f.bits & (0xff << 23)) >> 23;
    int newval = raw_exp + 1 - 127; // [-126, 129]
    return newval & 0x1ff;
}

static inline uint32_t FP32ToSFrac(float a, int width) {
    fp32_int32 f;
    f.f = a;
    uint32_t sgn = (f.bits & (0x1 << 31)) >> 31;
    uint32_t raw_exp = (f.bits & (0xff << 23)) >> 23;
    int64_t  raw_frac = f.bits & (0x7fffff);
    if(raw_exp) raw_frac |= 0x800000;
    if(sgn) raw_frac = -raw_frac; // Width == 25, s.24
    if(width < 25) raw_frac = raw_frac >> (25 - width);
    else raw_frac = raw_frac << (width - 25);
    return raw_frac & ((1ull << (unsigned long long)width) - 1);
}

template<int N>
constexpr int exp_i2() {
    return 1 << N;
}

template<int N>
constexpr float exp_f2() {
    if constexpr (N>0) return exp_f2<N-1>()*2;
    if constexpr (N<0) return exp_f2<N+1>()/2;
    if constexpr (N==0) return 1.;
    return 0.; // This should never happen, but just in case.
}

template<int _E> //, int M=7-E>  1.7 bits!
struct FP8 {
    uint8_t bits;
    using type = FP8<_E>;
    static constexpr int E      = _E;
    static constexpr int M      = (7-_E);
    static constexpr int E_BIAS = exp_i2<E-1>()-1;
    static constexpr float MAX  = (2-exp_f2<-M+1>())*exp_f2<exp_i2<E-1>()>();
    static constexpr float MIN  = exp_f2<-M>()*exp_f2<2-exp_i2<E-1>()>();
};
// template<int E>
// inline float to_float(const FP8<E>& in) {
//     fp32_int32 out = {0};
//     out.bits = in.bits & 0x80;
//     out.bits <<= 24;
//     uint32_t _bits = in.bits & 0x7F;
//     _bits <<= (23-FP8<E>::M);
//     out.bits |= _bits;
//     out.f *= exp_f2<127-FP8<E>::E_BIAS>();
//     return out.f;
// }
// TODO: Fixme, Not working in RISCV
static int isubnorm[8] = {
            0x00000000,
            0x3b000000,
            0x3b800000,
            0x3bc00000,
            0x3c000000,
            0x3c200000,
            0x3c400000,
            0x3c600000};
template<int E>
inline float FP8ToFP32(uint8_t r) {
    FP8<E> in;
    in.bits = r;
    fp32_int32 out = {0};
    out.bits = in.bits & 0x80;
    out.bits <<= 24;
    if(E == 4) {
        if((in.bits & 0x7f) < 0x08) {
            out.bits |= isubnorm[in.bits & 0x7f];
            return out.f;
        }
    }
    uint32_t _bits = in.bits & 0x7F;
    _bits <<= (23-FP8<E>::M);
    out.bits |= _bits;
    out.f *= exp_f2<127-FP8<E>::E_BIAS>();
    return out.f;
}
// template<int E>
// inline float fp8Mul(const FP8<E>& a, const FP8<E>& b) {
//     fp32_int32 out = {0};
//     out.bits = (a.bits ^ b.bits) & 0x80;
//     out.bits <<= 24;
//     // Calculate mantissa
//     long mant_a = (a.bits & ((1 << FP8<E>::M) - 1));
//     long mant_b = (b.bits & ((1 << FP8<E>::M) - 1));
//     // Calculate exponent
//     long exp_a = (a.bits >> FP8<E>::M) & ((1 << E) - 1);
//     long exp_b = (a.bits >> FP8<E>::M) & ((1 << E) - 1);
//     // Handle subnormal numbers
//     if(exp_a) mant_a |= (1 << FP8<E>::M); // mant_a is Q1.M
//     if(exp_b) mant_b |= (1 << FP8<E>::M); // mant_b is Q1.M
//     long exp = exp_a + exp_b - FP8<E>::E_BIAS;
//     long mant = mant_a * mant_b; // mant is Q2.2M
//     mant = (mant >> FP8<E>::M) + (mant & ((1 << FP8<E>::M) - 1)) ? 1 : 0; // mant is Q2.M
//     long mant_clz = __builtin_clz(mant);
//     const machine_width = sizeof(long) * 8;
//     long width = machine_width - mant_clz;
//     if(width == 0) return 0.0f;
//     return out.f;
// }
template<int E>
inline uint8_t FP32ToFP8(float value) {
    FP8<E> out;
    fp32_int32 in = {value};
    out.bits = (in.bits >> 24) & 0x80;
    in.bits &= 0x7fffffff;
    if (in.f >= FP8<E>::MAX) {
        out.bits |= 0x7E;
    } else if (in.f < FP8<E>::MIN) { // => 0.
    } else if(E == 4 && in.bits < 0x3C800000) { // 2^-6
        if(in.bits >= 0x3c500000)      out.bits |= 0x7;
        else if(in.bits >= 0x3c300000) out.bits |= 0x6;
        else if(in.bits >= 0x3c100000) out.bits |= 0x5;
        else if(in.bits >= 0x3be00000) out.bits |= 0x4;
        else if(in.bits >= 0x3ba00000) out.bits |= 0x3;
        else if(in.bits >= 0x3b400000) out.bits |= 0x2;
        else if(in.bits >  0x3a000000) out.bits |= 0x1;
        return out.bits;
    } else {
        in.f *= exp_f2<FP8<E>::E_BIAS-127>();
        uint32_t eps = (0x3fffff>>FP8<E>::M) + ((in.bits >> (23-FP8<E>::M)) & 0x1);
        in.bits += eps;
        out.bits |= (in.bits >> (23-FP8<E>::M)) & 0x7F;
    }
    return out.bits;
}

typedef int (*resultHandlerT)(int m, int n, float);

// Standard FP8 Matrix Multiplication Impl.
void CalcFP8MatMul(uint8_t* a, uint8_t* b, resultHandlerT c, int tM, int tN, int tK) {
    float A[tK];
    for(int m = 0 ; m < tM; m ++) {
        // A Array
        for(int k = 0; k < tK; k ++) {
            A[k] = FP8ToFP32<4>(a[m * tK + k]);
        }
        for(int n = 0 ; n < tN ; n ++) {
            float acc = 0.f;
            for(int k = 0; k < tK; k ++) {
                acc += A[k] * FP8ToFP32<4>(b[n * tK + k]);
            }
            // c[m * tN] = acc;
            if(c(m, n, acc)) return;
        }
    }
}
