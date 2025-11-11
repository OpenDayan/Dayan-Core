#ifndef IPC_CHANNEL_H
#define IPC_CHANNEL_H

#include <sys/types.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>
#include <stdint.h>
#include <string.h>
#include <errno.h>

/* 服务器初始化 - 创建监听Unix socket并接受连接 */
static inline int rvt_socket_server_init(const char* socket_path) {
    int server_fd, client_fd;
    struct sockaddr_un server_addr;
    
    /* 创建socket */
    if ((server_fd = socket(AF_UNIX, SOCK_STREAM, 0)) < 0) {
        return -1;
    }
    
    /* 删除可能已存在的socket文件 */
    unlink(socket_path);
    
    /* 绑定地址 */
    memset(&server_addr, 0, sizeof(server_addr));
    server_addr.sun_family = AF_UNIX;
    strncpy(server_addr.sun_path, socket_path, sizeof(server_addr.sun_path) - 1);
    
    if (bind(server_fd, (struct sockaddr*)&server_addr, sizeof(server_addr)) < 0) {
        close(server_fd);
        return -1;
    }
    
    /* 开始监听 */
    if (listen(server_fd, 1) < 0) {
        close(server_fd);
        return -1;
    }
    
    /* 接受客户端连接 */
    if ((client_fd = accept(server_fd, NULL, NULL)) < 0) {
        close(server_fd);
        return -1;
    }
    
    close(server_fd);  /* 关闭监听socket，只保留连接socket */
    return client_fd;
}

/* 客户端初始化 - 连接到Unix socket服务器 */
static inline int rvt_socket_client_init(const char* socket_path) {
    int sockfd;
    struct sockaddr_un server_addr;
    
    /* 创建socket */
    if ((sockfd = socket(AF_UNIX, SOCK_STREAM, 0)) < 0) {
        return -1;
    }
    
    /* 设置服务器地址 */
    memset(&server_addr, 0, sizeof(server_addr));
    server_addr.sun_family = AF_UNIX;
    strncpy(server_addr.sun_path, socket_path, sizeof(server_addr.sun_path) - 1);
    
    /* 连接服务器 */
    if (connect(sockfd, (struct sockaddr*)&server_addr, sizeof(server_addr)) < 0) {
        close(sockfd);
        return -1;
    }
    
    return sockfd;
}

/* 通用的push函数 - 发送uint64_t数据 */
static inline int rvt_socket_push(int sockfd, uint64_t data) {
    ssize_t total_sent = 0;
    ssize_t sent;
    
    /* 阻塞式发送，确保发送完整的8字节 */
    while (total_sent < sizeof(uint64_t)) {
        sent = send(sockfd, (char*)&data + total_sent, 
                   sizeof(uint64_t) - total_sent, 0);
        if (sent <= 0) {
            return -1;  /* 发送出错 */
        }
        total_sent += sent;
    }
    
    return 0;
}

/* 通用的pop函数 - 接收uint64_t数据 */
static inline int rvt_socket_pop(int sockfd, uint64_t* data) {
    ssize_t total_received = 0;
    ssize_t received;
    
    /* 阻塞式接收，确保接收完整的8字节 */
    while (total_received < sizeof(uint64_t)) {
        received = recv(sockfd, (char*)data + total_received, 
                       sizeof(uint64_t) - total_received, 0);
        if (received <= 0) {
            return -1;  /* 接收出错或连接关闭 */
        }
        total_received += received;
    }
    
    return 0;
}

/* 清理函数 - 删除socket文件 */
static inline void rvt_cleanup_socket(const char* socket_path) {
    unlink(socket_path);
}

#endif // IPC_CHANNEL_H
