build/VMatTestTopFast:
	mkdir -p build/VMatTestTopFast

build/VMatTestTopFast/VMatTestTop: generated/MatTestTop/filelist.f build/VMatTestTopFast
	verilator -j 0 --assert --trace --trace-structs --trace-fst -CFLAGS -O3 -CFLAGS -DVL_USER_FATAL -CFLAGS -DVL_USER_STOP -CFLAGS -Wno-narrowing --top MatTestTop -Mdir build/VMatTestTopFast -y generated/MatTestTop/ --cc -f generated/MatTestTop/filelist.f --exe csrc/test/MatTestTopTest.cpp
	make -C build/VMatTestTopFast -f VMatTestTop.mk VMatTestTop

validate_VMatTestTopFast:
	make -C build/VMatTestTopFast -f VMatTestTop.mk VMatTestTop
	./build/VMatTestTopFast/VMatTestTop 4 2 16 -1
	surver wave/VMatTestTopFast.fst --port 4000 --token 00000000