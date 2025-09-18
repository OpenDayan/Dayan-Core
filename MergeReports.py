import os
import json
import glob
import csv
import re

def extract_mnk_from_key(key):
    """
    从键名中提取 M、N、K 值
    
    Args:
        key (str): 格式为 FP8M{M}N{N}K{K} 的键名
        
    Returns:
        tuple: (M, N, K) 的整数值，如果提取失败则返回 (None, None, None)
    """
    pattern = r'FP8M(\d+)N(\d+)K(\d+)'
    match = re.search(pattern, key)
    if match:
        return int(match.group(1)), int(match.group(2)), int(match.group(3))
    return None, None, None

def convert_rpt_to_csv_with_mnk(input_folder, output_file):
    """
    将文件夹中所有.rpt文件合并为一个CSV文件，并提取键名中的M、N、K信息
    
    Args:
        input_folder (str): 包含.rpt文件的文件夹路径
        output_file (str): 输出的CSV文件路径
    """
    # 获取所有.rpt文件
    rpt_files = glob.glob(os.path.join(input_folder, "*.rpt"))
    
    # 存储所有数据
    all_data = []
    fieldnames = ['M', 'N', 'K', 'Key']  # CSV文件的列名，前三列是M、N、K
    
    # 遍历所有文件
    for file_path in rpt_files:
        try:
            with open(file_path, 'r', encoding='utf-8') as f:
                content = f.read().strip()
                
                # 解析JSON内容
                if content:
                    # 找到第一个冒号的位置
                    colon_pos = content.find(':')
                    if colon_pos > 0:
                        key = content[:colon_pos].strip().strip('"')
                        value_str = content[colon_pos+1:].strip()
                        
                        # 解析值部分
                        try:
                            value_data = json.loads(value_str)
                            
                            # 从键名中提取M、N、K
                            m, n, k = extract_mnk_from_key(key)
                            
                            # 创建数据行
                            row_data = {'M': m, 'N': n, 'K': k, 'Key': key}
                            
                            # 添加所有字段到行数据
                            for field_name, field_value in value_data.items():
                                row_data[field_name] = field_value
                                # 确保字段名列表包含所有可能的字段
                                if field_name not in fieldnames:
                                    fieldnames.append(field_name)
                            
                            all_data.append(row_data)
                        except json.JSONDecodeError:
                            print(f"警告: 文件 {file_path} 的值部分不是有效的JSON: {value_str}")
        except Exception as e:
            print(f"处理文件 {file_path} 时出错: {str(e)}")
    
    # 将数据写入CSV文件
    with open(output_file, 'w', newline='', encoding='utf-8') as f:
        writer = csv.DictWriter(f, fieldnames=fieldnames)
        writer.writeheader()
        writer.writerows(all_data)
    
    print(f"成功转换 {len(all_data)} 个文件到 {output_file}")

# 使用示例
if __name__ == "__main__":
    input_folder = "reports"  # 当前目录，你可以修改为你的文件夹路径
    output_file = "reports.MatmulM16N8K32.csv"
    
    convert_rpt_to_csv_with_mnk(input_folder, output_file)