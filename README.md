# MCP Tester

A web-based MCP test-tool which also supports features like the mcp-ui package and the ui features offered by mcp-fs.

# Running

In the backend folder:

```
./mvnw quarkus:dev
```

Or

```
mvn quarkus:dev
```

In the frontend:

```
npm install
npm run dev
```

## Debug MCP bridge messages

Run this in the browser console to log MCP postMessage traffic from the host page:

```js
window.addEventListener("message", (e) => {
  if (e?.data?.type?.startsWith("mcp:")) {
    console.log("[host]", e.data);
  }
});
```
