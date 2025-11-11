#include <stdint.h>
#include <string>
#include <queue>
#include <svdpi.h>
#include "configuration.h"
#include "dram_system.h"
#include "rvtng-intf.h"

#define NPU_FREQ (1000.f) // MHz
#define MEM_FREQ (1200.f) // MHz

#define DRAM_CYCLE (1000.f / MEM_FREQ) // 1200MHz -> 0.833ns
#define NPU_CYCLE  (1000.f / NPU_FREQ)  // 300MHz  -> 3.3ns

std::string ini_file = "csrc/DRAMsim3/configs/DDR4_8Gb_x16_2400.ini";

std::queue<std::pair<uint64_t, uint64_t>> read_resp;
static void call_back_read(uint64_t addr, uint64_t id) {
    read_resp.push(std::make_pair(addr, id));
}

std::queue<std::pair<uint64_t, uint64_t>> write_resp;
static void call_back_write(uint64_t addr, uint64_t id) {
    write_resp.push(std::make_pair(addr, id));

}

static int data_bytes_ = 0;

extern "C" void *DPIAXI4Init(
                             int id,
                             int addr_bits,
                             int data_bits
			     ) {
    printf("Memory Simulator: Get Init signal id:%d, addr_bits:%d, data_bits:%d.\n", id, addr_bits, data_bits);
    // 创建 DRAMSim3 handler
    
    auto *config = new dramsim3::Config("csrc/DRAMsim3/configs/DDR4_8Gb_x8_2400.ini", ".");

    auto *sys = new dramsim3::JedecDRAMSystem(*config, ".", call_back_read, call_back_write);

    data_bytes_ = data_bits / 8;

    return (void*)sys;
}

// 交互 fd
extern long fdInst, fdMsg;
extern "C" void DPIAXI4Tick(
        void *channel,

        unsigned char reset,

        unsigned char ar_valid,
        unsigned char *ar_ready,
        long long int ar_addr,
        int ar_id,
        int ar_size,
        int ar_len,

        unsigned char aw_valid,
        unsigned char *aw_ready,
        long long int aw_addr,
        int aw_id,
        int aw_size,
        int aw_len,

        unsigned char w_valid,
        unsigned char *w_ready,
        int w_strb,
        const svOpenArrayHandle w_data,
        unsigned char w_last,

        unsigned char *r_valid,
        unsigned char r_ready,
        int *r_id,
        int *r_resp,
        const svOpenArrayHandle r_data,
        unsigned char *r_last,

        unsigned char *b_valid,
        unsigned char b_ready,
        int *b_id,
        int *b_resp)
{
    dramsim3::JedecDRAMSystem *sys = (dramsim3::JedecDRAMSystem *)channel;
    if(ar_valid) {
        if(sys->WillAcceptTransaction(ar_addr, false)) {
            // 添加请求进入队列
            sys->AddTransaction(ar_addr, false);
            *ar_ready = 1;
        } else {
            *ar_ready = 0;
        }
    }
    // TODO: Fixme
    if(aw_valid & w_valid) {
        if(sys->WillAcceptTransaction(aw_addr, true)) {
            // 添加请求进入队列
            sys->AddTransaction(aw_addr, true);
            *aw_ready = 1;
            *w_ready = 1;
            // 立即完成写请求
            backend_dram_write(fdMsg, (char*)w_data, data_bytes_, aw_addr);
        } else {
            *aw_ready = 0;
            *w_ready = 0;
        }
    }
    
    static double npu_time = 0;
    static double dram_time = 0;
    static bool dram_ticked = false;

    npu_time += NPU_CYCLE;
    while(npu_time > dram_time) {
        sys->ClockTick();
        dram_time += DRAM_CYCLE;
    }

    if(!read_resp.empty()) {
        // TODO: Note, this is not actually correct with axi standard.
        if(r_ready) {
            auto resp = read_resp.front();
            *r_valid = 1;
            *r_id = resp.second;
            *r_last = 1;
            *r_resp = 0;
            backend_dram_read(fdMsg, (char*)r_data, data_bytes_, resp.first);
            read_resp.pop();
        } else {
            *r_valid = 0;
        }
    } else {
        *r_valid = 0;
    }

    if(!write_resp.empty()) {
        *b_valid = 1;
        *b_resp = 0;
        *b_id = write_resp.front().second;
        if(b_ready) {
            write_resp.pop();
        }
    } else {
        *b_valid = 0;
    }
}
