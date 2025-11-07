#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <ctype.h>
#include <fcntl.h>
#include <unistd.h>
#include <signal.h>
#include <sys/wait.h>
#include <sys/types.h>
#include <sys/prctl.h>

// 函数声明
void run_next_cycle(int n);
void run_until_empty(void);
void dump_waveform(void);
static inline void process_command(const char *command);
static inline int parse_number(const char *str, int *result);

/**
 * 启动子进程
 * 如果之前有存活的子进程则先杀死
 * @return 成功返回子进程PID，失败返回-1
 */
pid_t start_surfer_process(char* filename) {
    static pid_t child_pid = 0;
    
    // 检查并杀死已存在的子进程
    if (child_pid > 0) {
        // 检查进程是否存活
        if (kill(child_pid, 0) == 0) {
            // 先尝试优雅终止
            if (kill(child_pid, SIGTERM) == 0) {
                // 等待进程终止
                for (int i = 0; i < 50; ++i) {
                    if (kill(child_pid, 0) == -1) {
                        break; // 进程已终止
                    }
                    usleep(100000);
                }
                
                // 优雅终止失败，强制终止
                if (kill(child_pid, 0) == 0) {
                    kill(child_pid, SIGKILL);
                }
            }
        }
    }

    // 创建新的子进程
    pid_t pid = fork();
    
    if (pid == -1) {
        return -1;
    } 
    else if (pid == 0) {
        int devnull = open("/dev/null", O_RDONLY);
        if (devnull < 0) {
            perror("open /dev/null");
            exit(1);
        }
        dup2(devnull, STDIN_FILENO);
        close(devnull);
        // 子进程随父进程退出而结束
        prctl(PR_SET_PDEATHSIG, SIGHUP);
        // 子进程执行命令
        execlp("surver", "surver", filename, 
               "--port", "4000", "--token", "00000000", NULL);
        exit(EXIT_FAILURE);
    } 
    else {
        // 父进程记录信息
        child_pid = pid;
        
        // 设置信号处理避免僵尸进程
        signal(SIGCHLD, SIG_IGN);
        
        return pid;
    }
}

// 模拟的函数实现
static inline void run_next_cycle_abstract(int n) {
    printf("执行 run_next_cycle(%d)\n", n);
    run_next_cycle(n);
}

static inline void run_until_empty_abstract(void) {
    printf("执行 run_until_empty()\n");
    run_until_empty();
}

static inline void dump_waveform_abstract(void) {
    printf("执行 dump_waveform()\n");
    dump_waveform();
}

static inline int parse_number(const char *str, int *result) {
    char *endptr;
    long num = strtol(str, &endptr, 10);
    
    if (endptr == str || *endptr != '\0') {
        return 0;
    }
    
    if (num < -2147483647 || num > 2147483647) {
        return 0;
    }
    
    *result = (int)num;
    return 1;
}

static inline void process_command(const char *command) {
    while (isspace((unsigned char)*command)) {
        command++;
    }
    
    if (*command == '\0' || *command == '#') {
        return;
    }
    
    switch (command[0]) {
        case 'n': {
            const char *arg;
            // n 命令：n [十进制数字]
            if(command[1] == 'd') arg = command + 2;
            else arg = command + 1;
            
            // 跳过空格
            while (isspace((unsigned char)*arg)) {
                arg++;
            }
            
            if (*arg == '\0') {
                printf("错误：n 命令需要参数\n");
                return;
            }
            
            int n;
            if (parse_number(arg, &n)) {
                run_next_cycle(n);
            } else {
                printf("错误：无效的数字参数 '%s'\n", arg);
            }
            if(command[1] == 'd') {
                dump_waveform();
            }
            break;
        }
        
        case 'c':
            run_until_empty();
            break;
            
        case 'd':
            dump_waveform();
            break;
            
        default:
            printf("错误：未知命令 '%c'\n", command[0]);
            printf("可用命令：\n");
            printf("  n [数字] - 执行指定的周期数\n");
            printf("  c        - 运行直到仿真终止标志指令\n");
            printf("  d        - 导出波形数据，并启用 surfer 查看\n");
            printf("  q        - 退出\n");
            break;
    }
}

#include <errno.h>
void debug_stdin_status() {
    printf("stdin status:\n");
    printf("  feof: %d\n", feof(stdin));
    printf("  ferror: %d\n", ferror(stdin));
    printf("  errno: %d\n", errno);
    fflush(stdout);
}
void reset_stdin() {
    // 清除错误和 EOF 标志
    clearerr(stdin);
    
    // 检查并重置文件描述符标志
    int flags = fcntl(STDIN_FILENO, F_GETFL, 0);
    if (flags & O_NONBLOCK) {
        // 如果是非阻塞模式，可以改为阻塞模式
        fcntl(STDIN_FILENO, F_SETFL, flags & ~O_NONBLOCK);
    }
}

// RVT CLI
int cli_main() {
    char input[256];
    
    printf("=================== RVTNG CLI Shell ===================\n");
    printf("输入 'quit' 或 'exit' 退出 Shell\n");
    printf("输入 'help' 显示帮助信息\n\n");
    
    while (1) {
        printf("\033[32m➜\033[0m ");
        fflush(stdout);
        
        // 读取输入
        if (fgets(input, sizeof(input), stdin) == NULL) {
            printf("\nRVT CLI退出\n");
            debug_stdin_status();
            break; // 读取失败或EOF
        }
        
        // 去除换行符
        input[strcspn(input, "\n")] = '\0';
        
        // 检查退出命令
        if (strcmp(input, "quit") == 0 || strcmp(input, "exit") == 0 || strcmp(input, "q") == 0) {
            printf("RVT CLI退出\n");
            break;
        }
        
        // 检查帮助命令
        if (strcmp(input, "help") == 0) {
            printf("可用命令：\n");
            printf("  n [十进制数字] - 调用函数 run_next_cycle(int n)\n");
            printf("  nd [十进制数字] - 调用函数 run_next_cycle(int n);dump_waveform();\n");
            printf("  c              - 调用函数 run_until_empty()\n");
            printf("  d              - 调用函数 dump_waveform()\n");
            printf("  help           - 显示此帮助信息\n");
            printf("  q, quit, exit     - 退出RVT CLI\n");
            continue;
        }
        
        // 处理命令
        process_command(input);
    }
    
    return 0;
}
