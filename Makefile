VERILATOR_FLAGS = --top-module $(TOP) \
	--assert \
	--output-split 500 \
	--output-split-cfuncs 500 \
	-I$(abspath generated)

all: UnitTest.mk

include UnitTest.mk
include MatTest.mk

.PHONY: UnitTest.mk
UnitTest.mk: GenerateMakefile.py UnitTestConfig.json generated/testbenchTemplate
	python3 GenerateMakefile.py

generated/testbenchTemplate:
	mkdir -p generated/testbenchTemplate

wave:
	mkdir -p wave

clean:
	rm -rf build
	rm -rf generated
	rm -rf wave
	rm -rf UnitTest.mk
