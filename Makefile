VERILATOR_FLAGS = --top-module $(TOP) \
	--assert \
	--output-split 500 \
	--output-split-cfuncs 500 \
	-I$(abspath generated)

include UnitTest.mk

.PHONY: UnitTest.mk
UnitTest.mk: GenerateMakefile.py UnitTestConfig.json
	python3 GenerateMakefile.py

all: UnitTest.mk

build:
	mkdir build

