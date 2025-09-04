
def genBF16Test(M, N, K, space = 0):
    rndValueCnt = (M + N) * K
    strBuilder = '''// Test an {M}x{N}x{K} BF16-DPA
dut->bf16 = 1;
auto randArray = GenRandomFloats(({M} + {N}) * ({K} + 1), 10);
std::vector<float> r({M} * {N});
'''.format(M = M, N = N, K = K)
    for m in range(M):
        for k in range(K):
            strBuilder += '''dut->a_{m}_{kL} = getLower(FP32ToBF16(randArray[{i}]));
dut->a_{m}_{kH} = getUpper(FP32ToBF16(randArray[{i}]));
'''.format(m = m, kL = k * 2, kH = k * 2 + 1, i = m * K + k)
    for n in range(N):
        for k in range(K):
            strBuilder += '''dut->b_{n}_{kL} = getLower(FP32ToBF16(randArray[{i}]));
dut->b_{n}_{kH} = getUpper(FP32ToBF16(randArray[{i}]));
'''.format(n = n, kL = k * 2, kH = k * 2 + 1, i = M * K + n * K + k)
    for i in range(rndValueCnt):
        strBuilder += '''randArray[{i}] = BF16Clip(randArray[{i}]);
'''.format(i = i)
    for m in range(M):
        for n in range(N):
            strBuilder += "dut->cin_exp_{m}_{n} = FP32ToSExp(randArray[{M} * {K} + {N} * {K} + {i}]);\n".format(m = m, n = n, M = M, N = N, K = K, i = m * N + n)
            strBuilder += "dut->cin_frac_{m}_{n} = FP32ToSFrac(randArray[{M} * {K} + {N} * {K} + {i}], 25);\n".format(m = m, n = n, M = M, N = N, K = K, i = m * N + n)
            strBuilder += "r[{i}] = randArray[{M} * {K} + {N} * {K} + {i}] + ".format(m = m, n = n, M = M, N = N, K = K, i = m * N + n)
            for k in range(K):
                strBuilder += "randArray[{a}] * randArray[{b}]".format(a = m * K + k, b = M * K + n * K + k)
                if k != K - 1:
                    strBuilder += " + "
            strBuilder += ";\n"
    strBuilder += '''results.push(r);
'''
    space = space * ' '
    return space + strBuilder.replace('\n','\n' + space)

def genCheck(M, N):
    str_builder = ""
    template = 'FPCheck(r[{i}], *(float*)&dut->fp32Dbg_{m}_{n}, "{m}_{n}");\n'
    for m in range(M):
        for n in range(N):
            str_builder += template.format(i = m * N + n, m = m, n = n)
    return str_builder

M = 2
N = 2
K = 2

with open('FP8BF16FusedArrayTestBF16Gen.hpp', 'w') as f:
    f.write(genBF16Test(M,N,K,8))

with open('FP8BF16FusedArrayTestCheckGen.hpp', 'w') as f:
    f.write(genCheck(M,N))