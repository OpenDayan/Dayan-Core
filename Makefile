VERILATOR_FLAGS = --top-module $(TOP) \
	--assert \
	--output-split 500 \
	--output-split-cfuncs 500 \
	-I$(abspath generated)

build/VU8m2n2k1U4m2n2k2Mul/VU8m2n2k1U4m2n2k2Mul: generated/U8m2n2k1U4m2n2k2Mul/filelist.f build/VU8m2n2k1U4m2n2k2Mul
	verilator --trace --trace-fst -Mdir build/VU8m2n2k1U4m2n2k2Mul -y generated/U8m2n2k1U4m2n2k2Mul/ --cc -f generated/U8m2n2k1U4m2n2k2Mul/filelist.f --exe csrc/test/U8m2n2k1U4m2n2k2MulTest.cpp
	make -C build/VU8m2n2k1U4m2n2k2Mul -f VU8m2n2k1U4m2n2k2Mul.mk VU8m2n2k1U4m2n2k2Mul
	build/VU8m2n2k1U4m2n2k2Mul/VU8m2n2k1U4m2n2k2Mul --trace-fst
	surver VU8m2n2k1U4m2n2k2Mul.fst --port 4000 --token 00000000

.PHONY: generated/U8m2n2k1U4m2n2k2Mul/filelist.f
generated/U8m2n2k1U4m2n2k2Mul/filelist.f:
	./mill GPU-Chisel.runMain fpmath.U8m2n2k1U4m2n2k2Mul

build/VU8m2n2k1U4m2n2k2Mul: build
	mkdir build/VU8m2n2k1U4m2n2k2Mul

build:
	mkdir build

