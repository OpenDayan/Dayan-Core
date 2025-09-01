import json

with open('UnitTestConfig.json', 'r') as f:
    module_map = json.load(f)

# Generate One Module Makefile
def generateOneModuleMakefile(moduleName: str, modulePackage: str):
    return '''
build/V{Name}/V{Name}: generated/{Name}/filelist.f build/V{Name}
	verilator --trace --trace-fst -CFLAGS -Wno-narrowing -Mdir build/V{Name} -y generated/{Name}/ --cc -f generated/{Name}/filelist.f --exe csrc/test/{Name}Test.cpp
	make -C build/V{Name} -f V{Name}.mk V{Name}

.PHONY: generated/{Name}/filelist.f
generated/{Name}/filelist.f:
	./mill GPU-Chisel.runMain {Package}.{Name}

build/V{Name}: build
	mkdir build/V{Name}

wave/V{Name}.fst: build/V{Name}/V{Name}
	build/V{Name}/V{Name} --trace-fst

.PHONY: surver_{Name}
surver_{Name}: wave/V{Name}.fst
	surver wave/V{Name}.fst --port 4000 --token 00000000
'''.format(Name=moduleName, Package=modulePackage)

str_builder = 'all:'
for name in module_map:
    str_builder += ' wave/V{Name}.fst'.format(Name = name)

str_builder += '\n'
for name in module_map:
    str_builder += generateOneModuleMakefile(name, module_map[name]['Package'])

with open('UnitTest.mk', 'w') as f:
    f.write(str_builder)
