import json

testList = []

# For FFM
testList += [[i + 1, 256, 256] for i in range(256)]
testList += [[256, i + 1, 256] for i in range(256)]

# For MQA-Attention
# Query = [HeadNum * Seq, HDIM], Key = [Seq, HDIM] - Prefill
testList += [[6 * (s + 1), s + 1, 128] for s in range(128)]
# Query = [HeadNum, HDIM], Key = [Seq, HDIM] - Decode
testList += [[s + 1, 6, 128] for s in range(256)]
# Score = [Seq, Seq] BF16 Value = [HDIM, Seq] BF16
testList += [[128, s + 1, (s + 1) * 2] for s in range(256)]
testList += [[128, 1, (s + 1) * 2] for s in range(256)]

def generateMatTest(testList: list):
    strBuilder = '''.PHONY: testall
reports:
	mkdir -p reports
validate_VMatTestTop:
	make -C build/VMatTestTop -f VMatTestTop.mk VMatTestTop
	./build/VMatTestTop/VMatTestTop 64 64 128 -1
	surver wave/VMatTestTop.fst --port 4000 --token 00000000
'''
    for test in testList:
        strBuilder += '''
reports/{M}x{N}x{K}: reports
	./build/VMatTestTop/VMatTestTop {M} {N} {K} 1>reports/{M}x{N}x{K}.rpt 2>reports/{M}x{N}x{K}.err
testall: reports/{M}x{N}x{K}
'''.format(M = test[0], N = test[1], K = test[2])
    return strBuilder

print(generateMatTest(testList))
