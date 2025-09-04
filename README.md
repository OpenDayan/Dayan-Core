RVT-New
=======================

新 RVT 指令集设计实现仓库，使用 Chisel 6.6.0。

## 构建方式：
使用 `make UnitTest.mk` 构建单元测试 Makefile 文件

使用 `make -j all` 在 Verilator 中执行所有测试

## 查看波形

推荐使用 `surfer` 替代 `gtkwave` 作为波形查看器。

Surfer 采用服务端-客户端架构，可在服务端打开波形文件，并通过远程客户端网络访问服务端。

使用 Surfer 查看波形，在服务器集群上使用非常方便。

用户不再需要将波形下载到本地，再使用 gtkwave 打开这样的繁琐流程。

从这里获取 Surfer：[Surfer 官网](https://gitlab.com/surfer-project/surfer)

在服务器安装好 surver（Surfer 服务端） 并本地安装好 Surfer 后，建议配置 ssh 端口转发，以方便客户端访问服务器。

本项目 Makefile 中，已配置好查看波形的项目。这些项目以 surver_ 开头，如：

`make -j surver_FP8BF16FusedUnit`

运行该命令后，会自动生成待测硬件，并使用 Verilator 进行仿真生成波形，并使用 surver 打开波形提供服务。

surver 服务默认配置在 4000 端口，并使用 00000000 作为密钥。

在用户端（配置好端口转发后），可以使用 `surfer http://127.0.0.1:4000/00000000` 命令查看波形。

在使用 surfer 过程中，若波形存在更新，可以直接在 surfer gui 中使用刷新按钮更新，无需重启 surfer。
