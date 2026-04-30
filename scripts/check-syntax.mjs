import { spawnSync } from "node:child_process";

const files = [
  "server/index.js",
  "public/app.js",
  "scripts/backfill-clickhouse-100-days.mjs"
];

for (const file of files) {
  const result = spawnSync(process.execPath, ["--check", file], { stdio: "inherit" });
  if (result.status !== 0) process.exit(result.status ?? 1);
}
