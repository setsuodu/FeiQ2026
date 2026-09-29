# MiniFeiQ

模拟协议，可以与 FeiQ2013 通信。

# MiniFeiQ 抽象传输层补丁包

## 改动说明

1. **抽象传输层 `ITransport`**
   - 位置：`Transport/ITransport.cs`
   - 当前实现：`UdpTransport`（兼容飞秋2013）
   - 后续自己加 WebSocket / TCP / KCP 时，只需实现 `ITransport` 接口，然后在 `MainWindow.xaml.cs` 的 `OnLoaded` 里替换一行即可。

2. **协议与服务分离**
   - `Protocol/`：命令常量 + 报文解析/打包（与传输无关）
   - `Services/IpMsgService`：只依赖 `ITransport`，业务逻辑与底层解耦

3. **单文件发布就绪**
   - csproj 已配置 `PublishSingleFile` + `SelfContained` + `win-x64`
   - 发布命令见下方

4. **兼容性**
   - 保持原有 GBK + 2425 端口 + IPMSG 核心命令
   - 与飞秋2013 互通能力不变

## 目录结构（覆盖原项目）

```
src/MiniFeiQ/
├── App.xaml
├── App.xaml.cs
├── MainWindow.xaml
├── MainWindow.xaml.cs
├── MiniFeiQ.csproj
├── Protocol/
│   ├── IpMsgCommands.cs
│   └── IpMsgPacket.cs
├── Transport/
│   ├── ITransport.cs
│   └── UdpTransport.cs
└── Services/
    └── IpMsgService.cs
```

## 使用方法

1. 用本补丁包覆盖原 MiniFeiQ 的 `src/MiniFeiQ` 目录（或整个项目）
2. 还原并运行：

```bash
cd src/MiniFeiQ
dotnet restore
dotnet run
```

3. 单文件发布：

```bash
dotnet publish -c Release -r win-x64 --self-contained true ^
  -p:PublishSingleFile=true ^
  -p:IncludeNativeLibrariesForSelfExtract=true ^
  -p:EnableCompressionInSingleFile=true
```

发布产物在：`bin/Release/net10.0-windows/win-x64/publish/MiniFeiQ.exe`

## 如何扩展传输层

1. 新建类实现 `ITransport`
2. 在 `MainWindow.OnLoaded` 里把

```csharp
ITransport transport = new UdpTransport(port: 2425);
```

换成你的实现即可。上层 `IpMsgService` 完全不用动。
