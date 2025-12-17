/***************************************************************************************
 * Copyright (c) 2025 Institute of Computing Technology, Chinese Academy of
 *Sciences
 *
 * EMU is licensed under Mulan PSL v2.
 * You can use this software according to the terms and conditions of the Mulan
 *PSL v2. You may obtain a copy of Mulan PSL v2 at:
 *          http://license.coscl.org.cn/MulanPSL2
 *
 * THIS SOFTWARE IS PROVIDED ON AN "AS IS" BASIS, WITHOUT WARRANTIES OF ANY
 *KIND, EITHER EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO
 *NON-INFRINGEMENT, MERCHANTABILITY OR FIT FOR A PARTICULAR PURPOSE.
 *
 * See the Mulan PSL v2 for more details.
 ***************************************************************************************/
#ifndef RVTNG_INTF_H
#define RVTNG_INTF_H

#ifdef __cplusplus
extern "C" {
#endif

#include <stdio.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include "ipc_channel.h"
#include "blocking-queue.h"

typedef struct {
	uint64_t inst;
    uint64_t rd;
    uint64_t rs1;
    uint64_t rs2;
    uint64_t timestamp;
} rvt_pack_t;

typedef struct {
	uint64_t rd;
	uint64_t data;
    uint64_t timestamp;
} rvt_commit_t;

DEFINE_BLOCKING_QUEUE(rvt_pack_t, RVTPackQ)
DEFINE_BLOCKING_QUEUE(rvt_commit_t, RVTCommitQ)

// Frontend Service API
int frontend_dram_read_service(char *buf, size_t size, off_t addr);
int frontend_dram_write_service(char *buf, size_t size, off_t addr);
int frontend_instruction_fetch_service(int coreIdx, rvt_pack_t* pkg);
int frontend_instruction_commit_service(int coreIdx, rvt_commit_t* pkg);

static inline void backend_init(int* fdMsg, int* fdInst) {
    int retry = 0;
    while((*fdMsg = rvt_socket_client_init("/tmp/rv_frontend_msg")) == -1) {
        if(retry >= 20) {
            exit(-1);
        }
        printf("Backend Init msg client Fail, retrying %d...\n", ++retry);
        usleep(500000);
    }
    while((*fdInst = rvt_socket_server_init("/tmp/rv_frontend_inst")) == -1) {
        if(retry >= 20) {
            exit(-1);
        }
        printf("Backend Init inst server Fail, retrying %d...\n", ++retry);
        usleep(500000);
    }
}

static inline void frontend_init(int* fdMsg, int* fdInst) {
    int retry = 0;
    while((*fdMsg = rvt_socket_server_init("/tmp/rv_frontend_msg")) == -1) {
        if(retry >= 20) {
            exit(-1);
        }
        printf("Frontend Init msg server Fail, retrying %d...\n", ++retry);
        usleep(500000);
    }
    while((*fdInst = rvt_socket_client_init("/tmp/rv_frontend_inst")) == -1) {
        if(retry >= 20) {
            exit(-1);
        }
        printf("Frontend Init inst clint Fail, retrying %d...\n", ++retry);
        usleep(500000);
    }
}

static inline void* frontend_serve_inst_loop(void* arg) {
    int fdInst = (long)arg;
    uint64_t msg;
    while(1) {
        // Waiting status;
        if (rvt_socket_pop(fdInst, &msg) != 0) {
            printf("Fail to get inst req from server.\n");
            exit(1);
        }
        rvt_pack_t instPkg;
        int n = frontend_instruction_fetch_service(msg, &instPkg);
        rvt_socket_push(fdInst, n);
        if(n != 0) {
            rvt_socket_push(fdInst, instPkg.inst);
            rvt_socket_push(fdInst, instPkg.rd);
            rvt_socket_push(fdInst, instPkg.rs1);
            rvt_socket_push(fdInst, instPkg.rs2);
            rvt_socket_push(fdInst, instPkg.timestamp);
        }
    }
    return NULL;
}

// backend端：创建共享内存区域
static inline void* backend_get_shm_ptr(const char* shm_name, size_t sz) {
    // 创建共享内存对象
    int shm_fd = shm_open(shm_name, O_CREAT | O_RDWR, 0666);
    if (shm_fd == -1) {
        perror("shm_open failed");
        return NULL;
    }
    
    // 设置共享内存大小
    if (ftruncate(shm_fd, sz) == -1) {
        perror("ftruncate failed");
        close(shm_fd);
        return NULL;
    }
    
    // 内存映射
    void* ptr = mmap(NULL, sz, PROT_READ | PROT_WRITE, MAP_SHARED, shm_fd, 0);
    if (ptr == MAP_FAILED) {
        perror("mmap failed");
        close(shm_fd);
        return NULL;
    }
    
    close(shm_fd); // 文件描述符可以关闭，映射仍然有效
    
    printf("Backend: Created shared memory '%s' with size %zu at %p\n", 
           shm_name, sz, ptr);
    return ptr;
}

// frontend端：打开已创建的共享内存
static inline void* frontend_get_shm_ptr(const char* shm_name) {
    // 打开已存在的共享内存对象
    int shm_fd = shm_open(shm_name, O_RDWR, 0666);
    if (shm_fd == -1) {
        perror("shm_open failed");
        return NULL;
    }
    
    // 获取共享内存大小
    struct stat sb;
    if (fstat(shm_fd, &sb) == -1) {
        perror("fstat failed");
        close(shm_fd);
        return NULL;
    }
    size_t sz = sb.st_size;
    
    // 内存映射
    void* ptr = mmap(NULL, sz, PROT_READ | PROT_WRITE, MAP_SHARED, shm_fd, 0);
    if (ptr == MAP_FAILED) {
        perror("mmap failed");
        close(shm_fd);
        return NULL;
    }
    
    close(shm_fd); // 文件描述符可以关闭，映射仍然有效
    
    printf("Frontend: Opened shared memory '%s' with size %zu at %p\n", 
           shm_name, sz, ptr);
    return ptr;
}

// 清理共享内存（可选）
static inline void cleanup_shm_ptr(const char* shm_name, void* ptr, size_t sz) {
    if (ptr && ptr != MAP_FAILED) {
        munmap(ptr, sz);
    }    
    shm_unlink(shm_name);
    printf("Cleaned up shared memory '%s'\n", shm_name);
}
static inline void* frontend_serve_msg_loop(void* arg) {
    int fdMsg = (long)arg;
    uint64_t msg;
    while(1) {
        // Waiting status;
        if (rvt_socket_pop(fdMsg, &msg) != 0) {
            printf("Fail to get msg from server.\n");
            exit(1);
        }
        if(msg == 0x0) { // Instruction Commit
            // Get CoreIdx
            uint64_t coreIdx;
            rvt_commit_t commit_info;
            rvt_socket_pop(fdMsg, &coreIdx);
            rvt_socket_pop(fdMsg, &commit_info.rd);
            rvt_socket_pop(fdMsg, &commit_info.data);
            rvt_socket_pop(fdMsg, &commit_info.timestamp);
            frontend_instruction_commit_service(coreIdx, &commit_info);
        } else if(msg == 0x10) { // Memory Read
            // Get Addr and Size
            uint64_t addr, size, buf;
            rvt_socket_pop(fdMsg, &addr);
            rvt_socket_pop(fdMsg, &size);
            // Read Loop
            while(size) {
                uint64_t rSz = size < 8 ? size : 8;
                frontend_dram_read_service((char*)&buf, rSz, addr);
                rvt_socket_push(fdMsg, buf);
                addr += rSz;
                size -= rSz;
            }
        } else if(msg == 0x11) { // Memory Write
            // Get Addr and Size
            uint64_t addr, size, buf;
            rvt_socket_pop(fdMsg, &addr);
            rvt_socket_pop(fdMsg, &size);
            // Write Loop
            while(size) {
                uint64_t rSz = size < 8 ? size : 8;
                rvt_socket_pop(fdMsg, &buf);
                frontend_dram_write_service((char*)&buf, rSz, addr);
                addr += rSz;
                size -= rSz;
            }
        }
    }
    return NULL;
}

// Backend API
static inline int backend_dram_read(int fdMsg, char *buf, size_t size, size_t addr) {
    uint64_t t_buf;
    rvt_socket_push(fdMsg, 0x10ull);
    rvt_socket_push(fdMsg, (uint64_t)addr);
    rvt_socket_push(fdMsg, (uint64_t)size);
    while(size) {
        uint64_t rSz = size < 8 ? size : 8;
        rvt_socket_pop(fdMsg, &t_buf);
        memcpy(buf, &t_buf, rSz);
        buf += rSz;
        size -= rSz;
    }
    return 0;
}
static inline int backend_dram_write(int fdMsg, char *buf, size_t size, off_t addr) {
    uint64_t t_buf;
    rvt_socket_push(fdMsg, 0x11ull);
    rvt_socket_push(fdMsg, (uint64_t)addr);
    rvt_socket_push(fdMsg, (uint64_t)size);
    while(size) {
        uint64_t rSz = size < 8 ? size : 8;
        memcpy(&t_buf, buf, rSz);
        rvt_socket_push(fdMsg, t_buf);
        buf += rSz;
        size -= rSz;
    }
    return 0;
}
static inline int backend_instruction_fetch(int fdInst, int coreIdx, rvt_pack_t* pkg) {
    uint64_t msg;
    rvt_socket_push(fdInst, coreIdx);
    if (rvt_socket_pop(fdInst, &msg) != 0) {
        printf("Fail to fetch inst response from server.\n");
        exit(1);
    }
    if(msg == 0) {
        // No Instruction Valid.
        return 0;
    }
    rvt_socket_pop(fdInst, &(pkg->inst));
    rvt_socket_pop(fdInst, &(pkg->rd));
    rvt_socket_pop(fdInst, &(pkg->rs1));
    rvt_socket_pop(fdInst, &(pkg->rs2));
    rvt_socket_pop(fdInst, &(pkg->timestamp));
    return 1;
}
static inline int backend_instruction_commit(int fdMsg, int coreIdx, rvt_commit_t* pkg) {
    if(pkg->rd == 0) return 0;
    rvt_socket_push(fdMsg, 0x0);
    rvt_socket_push(fdMsg, coreIdx);
    rvt_socket_push(fdMsg, pkg->rd);
    rvt_socket_push(fdMsg, pkg->data);
    rvt_socket_push(fdMsg, pkg->timestamp);
    return 1;
}

#ifdef __cplusplus
}
#endif

#endif /* INTERFACE_H */
