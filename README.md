# Wayfarer Harness（行者）

Java 21 + Spring Boot 3.5 自研的 Coding Agent Harness：**三链责任链 Loop + 注解式工具 + 子 Agent + RSI 记忆/技能自沉淀（jgit 全量可回溯）**，附带单页 Web 控制台。

- agent 蓝图：`wayfarer`（标准）、`wayfarerpro`（+ 子 Agent）、`wayfarer-rsi`（+ 记忆与技能沉淀）
- 核心思想：Loop 只做一个 `while`，模型前后与工具前后的一切逻辑都挂在**环绕式责任链**上，互不侵入

***

## 快速开始

### 环境

| 依赖    | 版本                          |
| ----- | --------------------------- |
| JDK   | 21                          |
| Maven | 3.9+                        |
| 模型服务  | 任意 OpenAI 兼容端点（默认 DeepSeek） |

### 启动

```powershell
# 1. 配置模型 Key（也可启动后在「设置 → 模型配置」里填）
$env:DEEPSEEK_API_KEY="sk-..."

# 2. 启动
mvn spring-boot:run
```

打开 <http://localhost:8080> 。也可以打包运行：

```powershell
mvn -DskipTests package
java -jar target/wayfarer-harness-1.0-SNAPSHOT.jar
```

### 第一次对话

1. 底部 **工作区选择**：指向要操作的项目目录（编码工具只能读写该目录；`.wayfarer/root-workspace` 是放全局技能的根工作区）
2. 底部 **Agent**：选择 `wayfarer` / `wayfarerpro` / `wayfarer-rsi`
3. 底部 **审批级别**：`全部拦截` / `仅工作区内` / `不拦截`（对 `read`、`write`、`edit`、`bash` 生效）

### 目录速览

```
.wayfarer/
├── sessions/            # 会话 JSONL（消息、工具调用、状态），可回放
├── systemprompt/        # 系统提示词，文件名：Agent名-提示词名-序号.md
├── root-workspace/
│   └── skills/          # 全局技能（SKILL.md + frontmatter）
├── modelconfig/         # 多份模型配置（jsonl）
├── memorycompactconfig/ # 多份记忆压缩策略（jsonl）
├── shadow/              # 文件变更的影子 git 仓库（不污染工作区 .git）
└── rsi/
    ├── stores/<agent>/  # 沉淀产物：memory（L1/场景/画像）+ skills
    ├── git/<agent>/     # 沉淀仓库 git-dir（每次沉淀一次 commit）
    └── state/<agent>/   # 游标簿记（不入 git）
```

### 测试

```powershell
mvn test
```

***

## 能力概览

| 模块         | 能力                                                                  | 状态  |
| ---------- | ------------------------------------------------------------------- | --- |
| 模型层        | OpenAI 兼容接入、Flux 流式、取消、超时/重试、多配置热切换                                 | 已实现 |
| Agent Loop | modelChain / toolChain / runChain 三链、上下文状态机、最大轮次、随时停止               | 已实现 |
| 工具系统       | `@Tool` / `@ToolParam` 注解声明；read / write / edit / bash / plan 等内建工具 | 已实现 |
| 子 Agent    | 同步/异步派发、历史继承、配额与深度限制、父级监督收敛                                         | 已实现 |
| HITL 审批    | 编码工具三档拦截、前端确认、超时按未批准                                                | 已实现 |
| 上下文        | JSONL 会话持久化、记忆压缩、大消息卸载 + `read_unloaded` 回读                         | 已实现 |
| 提示词 / 技能   | 提示词文件化管理；SKILL.md 清单注入 + 按需读取；面板编辑                                  | 已实现 |
| 变更追溯       | 影子 git 记录工作区变更、diff 查看、随会话 reset 撤销                                 | 已实现 |
| RSI        | 四层记忆（L0-L3）+ 场景库 + 画像 + 技能自沉淀 + git diff/回溯                         | 已实现 |
|            |                                                                     |     |

***

## 未来展望

### 1. RSI 完整闭环：资产沉淀 → 审批 → 测评 → 回溯

### 2. 三层链、工具插件化：通过打包为插件，spi自动发现加载。

### 3. Agent 配置化：自由组合插件

当链、工具、提示词、技能、记忆策略都能声明后，Agent 本身就可以完全由配置描述
