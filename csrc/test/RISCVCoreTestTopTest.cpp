#include <stdlib.h>
#include <iostream>
#include <verilated.h>
#include "verilated_fst_c.h"
#include "VRISCVCoreTestTop.h"
#include "cliUtils.h"
#include "bar.h" // Added include for progress bar

VerilatedContext *contextp;
VerilatedFstC *m_trace;
VRISCVCoreTestTop *dut;
unsigned long long sim_time = 0;
unsigned int stopFlag = 0;

// Simple Memory Simulation
// Since we don't have a full TileLink Slave model here, we will implement a very basic response mechanism.
// Ideally, one would use a proper TL-Slave VIP.
// For this test, we will just toggle the ready signals and print outputs.

void run_next_cycle(uint64_t n) {
    unsigned long realN = n;
    for(unsigned long i = 0; i < realN;) {
        // Falling Edge
        dut->clock = 0;
        dut->eval();
        if(enable_waveform) m_trace->dump(contextp->time());
        contextp->timeInc(1);

        // Rising Edge
        dut->clock = 1;
        
        // Mock Memory / NPU Interactions
        // Always ready to accept requests (but won't respond with data, so Core might stall)
        dut->io_icacheTL_a_ready = 1;
        dut->io_dcacheTL_a_ready = 1;
        dut->io_npuReq_ready = 1;
        
        // Monitor NPU Requests
        if (dut->io_npuReq_valid && dut->io_npuReq_ready) {
            printf("[Time %llu] NPU Request: Inst=%08x RS1=%016llx RS2=%016llx\n", 
                sim_time, (unsigned int)dut->io_npuReq_bits_instWord, (unsigned long long)dut->io_npuReq_bits_rs1Data, (unsigned long long)dut->io_npuReq_bits_rs2Data);
        }

        // To make the core progress, we would need to feed instructions to io_icacheTL_d
        // This requires a state machine. For this basic setup, we just run the clock.
        
        dut->eval();
        if(enable_waveform) m_trace->dump(contextp->time());
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
        printf("\033[42;32mVerilator: RISCVCoreTestTop Run Next Cycle Success!\033[0m\n");
    } else {
        printf("\033[41;32mVerilator: RISCVCoreTestTop Run Next Cycle FAILED!\033[0m\n");
    }
}

void run_until_empty(void) {
    printf("Verilator: run_until_empty not implemented.\n");
}

static uint64_t dump_offset = 0;
void dump_waveform(void) {
     if(contextp->time() != dump_offset) {
        m_trace->close();
        start_surfer_process((char*) "wave/RISCVCoreTestTop.fst");
        delete m_trace;
        m_trace = new VerilatedFstC;
        dut->trace(m_trace, 99);
        dump_offset = contextp->time();
        m_trace->open("wave/RISCVCoreTestTop.fst");
    }
}

int main(int argc, char **argv)
{
    contextp = new VerilatedContext;
    contextp->commandArgs(argc, argv);
    dut = new VRISCVCoreTestTop;
    
    Verilated::traceEverOn(true);
    m_trace = new VerilatedFstC;
    dut->trace(m_trace, 99);
    m_trace->open("wave/RISCVCoreTestTop.fst");
    
    dut->clock = 0;
    dut->reset = 1;
    dut->eval();
    contextp->timeInc(1);
    
    // Reset Sequence
    for (int i = 0; i < 10; i++) {
        dut->clock ^= 1;
        dut->eval();
        contextp->timeInc(1);
    }
    dut->reset = 0;
    
    printf("RISCVCoreTestTop: Reset Complete. Starting Simulation...\n");
    
    cli_main(); // Enter interactive CLI
    
    // Cleanup
    dut->final();
    m_trace->close();
    delete dut;
    delete m_trace;
    delete contextp;
    return 0;
}

void vl_fatal(const char* filename, int line, const char* func, const char* msg) {
    std::cerr << "Fatal error at " << filename << ":" << line << " " << func << " " << msg << std::endl;
    stopFlag = 1;
}

void vl_stop(const char* filename, int line, const char* func) {
    std::cerr << "Stop at " << filename << ":" << line << " " << func << std::endl;
    stopFlag = 1;
}
