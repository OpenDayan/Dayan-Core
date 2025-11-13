#include <stdlib.h>
#include <iostream>
#include <verilated.h>
#include "verilated_fst_c.h"
#include "VRVTNGTestTop.h"
#include "cliUtils.h"
#include "rvtng-intf.h"
#include "bar.h"
#include <vector>
#include <queue>
VerilatedContext *contextp;
VerilatedFstC *m_trace;
VRVTNGTestTop *dut;
unsigned long long sim_time = 0;
unsigned int stopFlag = 0;

// 交互 fd
long fdInst, fdMsg;

#define LOG2BANK 5
#define URAMSIZE ((1 << LOG2BANK) * 32 * 1024ull)
long int *uram = NULL; // 1024KBytes URAM

extern "C" long long UltraRamRead(int id, int raddr)
{
    long long rtValue = uram[id | (raddr << LOG2BANK)];
    return rtValue;
}

extern "C" long long UltraRamWrite(int id, int waddr, long int wdata)
{
    uram[id | (waddr << LOG2BANK)] = wdata;
    return 0;
}

#include "rvtng-intf.h"

static inline void mainLoop()
{
    // Get Instructions
    rvt_pack_t inst;
    static int instCount = 0;

    dut->instResp_ready = 1;

    if(dut->instResp_valid) {
        rvt_commit_t commit;
        commit.data = dut->instResp_bits_data;
        commit.rd   = dut->instResp_bits_rd;
        backend_instruction_commit(fdMsg, 0, &commit);
    }

    if(dut->instReq_valid & dut->instReq_ready) {
        instCount = 0;
    }

    if(instCount == 0) {
        instCount = backend_instruction_fetch(fdInst, 0, &inst);
    }

    if(instCount != 0) {
        dut->instReq_valid = 1;
        dut->instReq_bits_instWord = (int)inst.inst;
        dut->instReq_bits_rdData   = inst.rd;
        dut->instReq_bits_rs1Data  = inst.rs1;
        dut->instReq_bits_rs2Data  = inst.rs2;
    } else {
        dut->instReq_valid = 0;
    }
}

static uint64_t dump_offset;

void run_next_cycle(uint64_t n) {
    unsigned long realN = n;
    for(unsigned long i = 0; i < realN;) {
        dut->clock ^= 1;
        dut->eval();
        mainLoop();
        if(enable_waveform) m_trace->dump(contextp->time() - dump_offset);
        contextp->timeInc(1);
        dut->clock ^= 1;
        dut->eval();
        if(enable_waveform) m_trace->dump(contextp->time() - dump_offset);
        contextp->timeInc(1);
        sim_time++;
        if(stopFlag != 0) {
            if((--stopFlag) == 1) break;
        }
        print_progress_with_speed_multithread(++i, realN, 0);
    }
    print_progress_with_speed_multithread(realN, realN, 0);
    wait_for_render_completion();
    if(!stopFlag) {
        printf("\033[42;32mVerilator: RVTNGTestTop Run Next Cycle Success!\033[0m\n");
    } else {
        printf("\033[41;32mVerilator: RVTNGTestTop Run Next Cycle FAILED!\033[0m\n");
    }
}

void run_until_empty(void) {
    printf("Verilator: TODO - Add support for run_until_empty.\n");
}

void dump_waveform(void) {
    if(contextp->time() != dump_offset) {
        m_trace->close();
        start_surfer_process((char*) "wave/RVTNGTestTop.fst");
        delete m_trace;
        m_trace = new VerilatedFstC;
        dut->trace(m_trace, 99);
        sleep(2);
        dump_offset = contextp->time();
        m_trace->open("wave/RVTNGTestTop.fst");
    }
}

int main(int argc, char **argv)
{
    // 创建 URAM
    uram = (long *)backend_get_shm_ptr("/uram_shm_0", 1024 * 1024);
    // 创建 QEMU 子进程
    pid_t qemuPid = fork();
    if (qemuPid == -1) {
        return -1;
    }
    else if (qemuPid == 0) {
        execlp("/workspace/npu-qemu/build/dl-riscv64", "QEMU_THREAD", "-monitor", "null",
        "-bios", "/workspace/npu-firmware/bl0/bootrom.elf",
        "-machine", "rvtng-v0", "-nographic", "-m", "4096", "-S", "-s", NULL);
        exit(EXIT_FAILURE);
    }
    // 父进程创建 server socket
    backend_init((int*)&fdMsg, (int*)&fdInst);
    printf("Main: QEMU Ready!\n");

    contextp = new VerilatedContext;
    contextp->commandArgs(argc, argv);
    dut = new VRVTNGTestTop;
    Verilated::traceEverOn(true);
    m_trace = new VerilatedFstC;
    dut->trace(m_trace, 99);
    m_trace->open("wave/RVTNGTestTop.fst");
    dut->clock = 0;
    dut->reset = 1;
    dut->eval();
    if(enable_waveform) m_trace->dump(contextp->time() - dump_offset);
    contextp->timeInc(1);
    for (int i = 0; i < 20; i++)
    {
        dut->clock ^= 1;
        dut->eval();
        if(enable_waveform) m_trace->dump(contextp->time() - dump_offset);
        contextp->timeInc(1);
    }
    dut->reset = 0;
    cli_main();
    exit(EXIT_SUCCESS);
}

void vl_fatal(const char* filename, int line, const char* func, const char* msg) {
    std::cerr << "Fatal error occurred at " << filename << ":" << line << " (function: " << func << ") - " << msg << std::endl;
    stopFlag = 5;
}

void vl_stop(const char* filename, int line, const char* func) {
    std::cerr << "stop occurred at " << filename << ":" << line << " (function: " << func << ")" << std::endl;
    stopFlag = 5;
}
