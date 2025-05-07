```sh
#docker:command=build
docker build -t jimclark106/agent:latest .
docker pull jimclark106/agent:latest
```

```sh
docker run -it --rm -e "MCP-GATEWAY_ENDPOINT=host.docker.internal:8811" jimclark106/agent:latest "hello world"
```

```sh
docker compose up
```

