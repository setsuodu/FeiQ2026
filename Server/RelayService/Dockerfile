FROM mcr.microsoft.com/dotnet/sdk:10.0 AS build
WORKDIR /src
COPY RelayServer.csproj .
RUN dotnet restore
COPY . .
RUN dotnet publish -c Release -o /app --self-contained false

FROM mcr.microsoft.com/dotnet/aspnet:10.0
WORKDIR /app
COPY --from=build /app .
EXPOSE 9000
ENV ASPNETCORE_URLS=http://0.0.0.0:9000
ENTRYPOINT ["dotnet", "RelayServer.dll"]
