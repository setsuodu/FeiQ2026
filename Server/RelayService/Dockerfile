FROM mcr.microsoft.com/dotnet/sdk:10.0 AS build
WORKDIR /src
COPY RelayService.csproj .
RUN dotnet restore
COPY . .
RUN dotnet publish -c Release -o /app --self-contained false

FROM mcr.microsoft.com/dotnet/aspnet:10.0
WORKDIR /app
COPY --from=build /app .
RUN mkdir -p /app/data
ENV ASPNETCORE_URLS=http://0.0.0.0:9000
ENV RELAY_DATA_DIR=/app/data
EXPOSE 9000
VOLUME ["/app/data"]
ENTRYPOINT ["dotnet", "RelayService.dll"]
