#!/usr/bin/env bash
set -euo pipefail
: "${WASMX_DAEMON_BIN:?}" "${WASMX_CLI_BIN:?}" "${WASMX_CLI_CONFIG:?}"
: "${LL_DAEMON_BIN:?}" "${LL_CLI_BIN:?}" "${LL_DAEMON_CONFIG:?}" "${LL_CLI_CONFIG:?}"
for x in curl jq base64 sha256sum; do command -v "$x" >/dev/null; done
R="$(mktemp -d)"; WP=""; LP=""
cleanup(){ local rc=$?; set +e; if ((rc)); then echo "carrier failed rc=$rc" >&2; cat "$R/w.log" "$R/l.log" 2>/dev/null >&2; fi; [[ -n "$WP" ]] && kill "$WP" 2>/dev/null && wait "$WP" 2>/dev/null; [[ -n "$LP" ]] && kill "$LP" 2>/dev/null && wait "$LP" 2>/dev/null; rm -rf "$R"; }; trap cleanup EXIT
note(){ echo "::notice title=wasm-runtime-carrier::$*"; }
WT='wasmx-0123456789abcdef0123456789abcdef0123456789abcdef'; LT='ll-0123456789abcdef0123456789abcdef0123456789abcdef'
printf '%s\n' "$WT" >"$R/wtok"; printf '%s\n' "$LT" >"$R/ltok"; chmod 600 "$R/wtok" "$R/ltok"; mkdir -p "$R/wh" "$R/lh"
printf '%s' 'AGFzbQEAAAABCwJgAn9/AX9gAAF/AhYBBXdhc214DG91dHB1dF93cml0ZQAAAwIBAQUDAQABBxcCBm1lbW9yeQIACndhc214X21haW4AAQoNAQsAQQBBHRAAGkEACwsjAQBBAAsdeyJvayI6dHJ1ZSwicnVudGltZSI6Indhc214In0='|base64 -d >"$R/w.wasm"
printf '%s' 'AGFzbQEAAAABCwJgAn9/AX9gAAF/AhYBBXdhc214DG91dHB1dF93cml0ZQAAAwIBAQUDAQACBxcCBm1lbW9yeQIACndhc214X21haW4AAQoNAQsAQQBBHRAAGkEACwsjAQBBAAsdeyJvayI6dHJ1ZSwicnVudGltZSI6Indhc214In0='|base64 -d >"$R/w2.wasm"
printf '%s' 'AGFzbQEAAAABBAFgAAADAgEABwoBBl9zdGFydAAACgQBAgAL'|base64 -d >"$R/l.wasm"
sha(){ sha256sum "$1"|cut -d' ' -f1; }; b64(){ base64 -w0 "$1"; }
wait_port(){ local p=$1 pid=$2 log=$3; for _ in $(seq 1 160); do curl -fsS "http://127.0.0.1:$p/healthz" >/dev/null 2>&1&&return; kill -0 "$pid" 2>/dev/null||{ cat "$log" >&2; return 1; }; sleep .25; done; cat "$log" >&2; return 1; }
start_w(){ HOME="$R/wh" WASMX_DESKTOP_ADDR=127.0.0.1:8766 WASMX_DESKTOP_TOKEN_FILE="$R/wtok" WASMX_ARTIFACT_ROOT="$R/wa" "$WASMX_DAEMON_BIN" >"$R/w.log" 2>&1 & WP=$!; wait_port 8766 "$WP" "$R/w.log"; }
start_l(){ HOME="$R/lh" USERPROFILE="$R/lh" LL_DESKTOP_FLAGS_CONFIG="$LL_DAEMON_CONFIG" LL_DESKTOP_ADDR=127.0.0.1:8763 LL_DESKTOP_TOKEN_FILE="$R/ltok" "$LL_DAEMON_BIN" >"$R/l.log" 2>&1 & LP=$!; wait_port 8763 "$LP" "$R/l.log"; }
stop_w(){ kill "$WP"; wait "$WP"||true; WP=""; }; stop_l(){ kill "$LP"; wait "$LP"||true; LP=""; }
wcli(){ HOME="$R/wh" WASMX_DESKTOP_TOKEN_FILE="$R/wtok" WASMX_DESKTOP_FLAGS_CONFIG="$WASMX_CLI_CONFIG" WASMX_DESKTOP_DAEMON_URL=http://127.0.0.1:8766 "$WASMX_CLI_BIN" "$@"; }
lcli(){ HOME="$R/lh" USERPROFILE="$R/lh" LL_DESKTOP_TOKEN_FILE="$R/ltok" LL_DESKTOP_FLAGS_CONFIG="$LL_CLI_CONFIG" LL_DESKTOP_DAEMON_URL=http://127.0.0.1:8763 "$LL_CLI_BIN" "$@"; }
adapter(){ local k=$1 o=$2; if [[ $k == w ]]; then jq -nc '{schema_version:"ores.lambda.adapter/v1",generated_by:"ores-stack",provider:"wasm_xprs",runtime_repository:"wasm-xprs/wasmx-lambdas",runtime_contract:"wasm-xprs.lambda-runtime/v1",execution_boundary:"wasmtime_store_instance",isolation_model:"fresh_store_and_instance_per_invocation",artifact_kind:"wasm_module",module_cache_policy:"compiled_module_allowed",invocation_instance_reuse:"forbidden",ambient_import_policy:"explicit_wasmx_v1_only",durable_state:"external_only",source:"src/routes/echo/lambda.rs",source_sha256:("c"*64)}' >"$o"; else jq -nc '{schema_version:"ores.lambda.adapter/v1",generated_by:"ores-stack",provider:"lunatic_lorry",runtime_repository:"lunatic-lorry/ll-lambdas",runtime_contract:"lunatic-lorry.lambda-runtime/v1",execution_boundary:"lunatic_process",isolation_model:"fresh_wasm_actor_per_invocation",artifact_kind:"wasm_module",module_cache_policy:"module_bytes_or_compiled_module_allowed",invocation_instance_reuse:"forbidden",ambient_import_policy:"explicit_lunatic_capabilities_only",durable_state:"external_only",source:"src/routes/echo/lambda.rs",source_sha256:("c"*64)}' >"$o"; fi; }
receipt(){ local k=$1 m=$2 a=$3 o=$4 p rr rc t ap xp; if [[ $k == w ]]; then p=wasm_xprs; rr=wasm-xprs/wasmx-lambdas; rc=wasm-xprs.lambda-runtime/v1; t=wasm32-unknown-unknown; ap=build/lambda/wasm_xprs/module.wasm; xp=generated/lambda-adapters/wasm_xprs/adapter.json; else p=lunatic_lorry; rr=lunatic-lorry/ll-lambdas; rc=lunatic-lorry.lambda-runtime/v1; t=wasm32-wasip1; ap=build/lambda/lunatic_lorry/module.wasm; xp=generated/lambda-adapters/lunatic_lorry/adapter.json; fi; jq -nc --arg p "$p" --arg rr "$rr" --arg rc "$rc" --arg t "$t" --arg ap "$ap" --arg xp "$xp" --arg as "$(sha "$m")" --arg ds "$(sha "$a")" '{schema_version:"ores.lambda.wasm-artifact.receipt/v1",generated_by:"ores-stack",provider:$p,runtime_repository:$rr,runtime_contract:$rc,adapter_contract:"ores.lambda.adapter/v1",artifact_path:$ap,artifact_sha256:$as,deploy_mutation_performed:false,target_triple:$t,adapter_path:$xp,adapter_sha256:$ds,source_path:"src/routes/echo/lambda.rs",source_sha256:("c"*64),cargo_manifest_path:"Cargo.toml",cargo_package:"fixture",cargo_target_name:"ores_wasm_lambda_unit",wrapper_sha256:("d"*64),unit_manifest_sha256:("e"*64)}' >"$o"; }
adapter w "$R/wa.json"; receipt w "$R/w.wasm" "$R/wa.json" "$R/wr.json"; adapter l "$R/la.json"; receipt l "$R/l.wasm" "$R/la.json" "$R/lr.json"
note 'start both canonical ports'; start_w; start_l
wc=$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8766/v1/status); lc=$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8763/v1/status); echo "unauth status: wasmx=$wc lunatic=$lc"; [[ $wc == 401 && $lc == 401 ]]
note 'authenticated runtime contracts'
curl -fsS -H "Authorization: Bearer $WT" http://127.0.0.1:8766/v1/status|jq -e '.runtime=="wasmtime" and .target_triple=="wasm32-unknown-unknown" and .wasi_enabled==false and .store_per_invocation==true'>/dev/null
curl -fsS -H "Authorization: Bearer $LT" http://127.0.0.1:8763/v1/status|jq -e '.runtime=="lunatic_wasm" and .actor_reusable==false and .worker_mode=="embedded_fresh_lunatic_actor"'>/dev/null
note 'wasmx receipt deploy and invoke'
wcli deploy --tenant tw --deployment r1 --module "$R/w.wasm" --ores-adapter "$R/wa.json" --ores-receipt "$R/wr.json" >"$R/wd.json"; jq -e '.compiled and .ores_adapter_verified and .ores_receipt_verified' "$R/wd.json">/dev/null
wcli invoke --tenant tw --deployment r1 --payload '{}' >"$R/wi.json"; jq -e '.ok and .payload_json=={"ok":true,"runtime":"wasmx"}' "$R/wi.json">/dev/null
note 'wasmx immutable deployment id'
body=$(jq -nc --arg w "$(b64 "$R/w2.wasm")" '{tenant_id:"tw",deployment_id:"r1",wasm_base64:$w}'); [[ $(curl -s -o "$R/conflict" -w '%{http_code}' -H "Authorization: Bearer $WT" -H 'content-type: application/json' -d "$body" http://127.0.0.1:8766/v1/deploy) == 409 ]]
note 'wasmx restart persistence and tamper detection'
stop_w; start_w; note 'wasmx restarted'; wcli invoke --tenant tw --deployment r1 --payload '{}' >"$R/wrestart.json" || { cat "$R/wrestart.json" >&2; exit 1; }; cat "$R/wrestart.json"; jq -e '.ok and .payload_json.runtime=="wasmx"' "$R/wrestart.json" >/dev/null
cp "$R/w2.wasm" "$R/wa/tw/r1/module.wasm"; set +e; wcli invoke --tenant tw --deployment r1 --payload '{}' >"$R/tamper" 2>"$R/tamper.err"; trc=$?; set -e; echo "wasmx tamper cli rc=$trc"; cat "$R/tamper" "$R/tamper.err"; ((trc != 0)); grep -Eqi 'integrity|manifest|deployment' "$R/tamper.err"
note 'lunatic receipt deploy and actor failure containment'
lcli deploy --tenant tl --deployment r1 --module "$R/l.wasm" --ores-adapter "$R/la.json" --ores-receipt "$R/lr.json" >"$R/ld.json"; jq -e '.ores_adapter_verified and .ores_receipt_verified' "$R/ld.json">/dev/null
lcli invoke --tenant tl --deployment r1 --payload '{}' >"$R/li.json"; jq -e '.ok==false and (.error|type=="string")' "$R/li.json">/dev/null
curl -fsS -H "Authorization: Bearer $LT" http://127.0.0.1:8763/v1/status|jq -e '.failed>=1 and .completed>=1 and .actor_reusable==false'>/dev/null
note 'lunatic restart persistence and tamper detection'
stop_l; start_l; lcli invoke --tenant tl --deployment r1 --payload '{}'|jq -e '.ok==false'>/dev/null
cp "$R/w.wasm" "$R/lh/.lunatic-lorry/deployments/tl/r1/module.wasm"; lcli invoke --tenant tl --deployment r1 --payload '{}'|jq -e '.ok==false and (.error|test("integrity|manifest|module";"i"))'>/dev/null
curl -fsS -H "Authorization: Bearer $LT" http://127.0.0.1:8763/v1/status >/dev/null
echo 'wasm runtime carrier: PASS'
