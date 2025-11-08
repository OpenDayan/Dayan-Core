#ifndef CLI_UTILS_H
#define CLI_UTILS_H

#include <cstdint>

// 函数声明
void run_next_cycle(uint64_t n);
void run_until_empty(void);
void dump_waveform(void);
pid_t start_surfer_process(char* filename);
int cli_main();

#endif