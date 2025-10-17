VERILATOR_FLAGS = --top-module $(TOP) \
	--assert \
	--output-split 500 \
	--output-split-cfuncs 500 \
	-I$(abspath generated)

all: UnitTest.mk

include UnitTest.mk

.PHONY: UnitTest.mk CodeStatistics
UnitTest.mk: GenerateMakefile.py UnitTestConfig.json generated/testbenchTemplate
	python3 GenerateMakefile.py

CodeStatistics:
	git ls-files | xargs wc -l

CodeStatisticsScala:
	git ls-files | grep "scala" | xargs wc -l

generated/testbenchTemplate:
	mkdir -p generated/testbenchTemplate

run_mattest:
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
