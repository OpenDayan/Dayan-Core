VERILATOR_FLAGS = --top-module $(TOP) \
	--assert \
	--output-split 500 \
	--output-split-cfuncs 500 \
	-I$(abspath generated)

all: UnitTest.mk

include UnitTest.mk

.PHONY: UnitTest.mk
UnitTest.mk: GenerateMakefile.py UnitTestConfig.json generated/testbenchTemplate
	python3 GenerateMakefile.py

generated/testbenchTemplate:
	mkdir -p generated/testbenchTemplate

build/VMatTestTopFast:
	mkdir -p build/VMatTestTopFast

build/VMatTestTopFast/VMatTestTop: generated/MatTestTop/filelist.f build/VMatTestTopFast
	verilator -j 0 --assert --trace --trace-structs --trace-fst -CFLAGS -O3 -CFLAGS -DVL_USER_FATAL -CFLAGS -DVL_USER_STOP -CFLAGS -Wno-narrowing --top MatTestTop -Mdir build/VMatTestTopFast -y generated/MatTestTop/ --cc -f generated/MatTestTop/filelist.f --exe csrc/test/MatTestTopTest.cpp
	$(MAKE) -C build/VMatTestTopFast -f VMatTestTop.mk VMatTestTop

run_mattest: build/VMatTestTopFast/VMatTestTop
	python3 GenerateMatTest.py > MatTest.mk
	$(MAKE) -f MatTest.mk testall
	python3 MergeReports.py

wave:
	mkdir -p wave

clean:
	rm -rf build
	rm -rf generated
	rm -rf wave
	rm -rf UnitTest.mk
