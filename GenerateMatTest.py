import json

testList = []

# For FFM
# testList +=  [[i + 1, 256, 1536] for i in range(256)]
# testList += [[256, i + 1, 1536] for i in range(256)]

# For MQA-Attention-Prefill
# Query = [HeadNum, HDIM], Key = [HeadNum/6, HDIM]
testList += [[6, 1, 128]]

def generateMatTest(testList: list):
    strBuilder = '''.PHONY: testall
reports:
	mkdir -p reports
validate_VMatTestTopFast:
	make -C build/VMatTestTopFast -f VMatTestTop.mk VMatTestTop
	./build/VMatTestTopFast/VMatTestTop 64 64 128 -1
	surver wave/VMatTestTopFast.fst --port 4000 --token 00000000
'''
    for test in testList:
        strBuilder += '''
reports/{M}x{N}x{K}: reports
	./build/VMatTestTopFast/VMatTestTop {M} {N} {K} 1>reports/{M}x{N}x{K}.rpt 2>reports/{M}x{N}x{K}.err
testall: reports/{M}x{N}x{K}
'''.format(M = test[0], N = test[1], K = test[2])
    return strBuilder

print(generateMatTest(testList))
