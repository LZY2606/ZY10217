'use strict';

let STATE = null;
const SVGNS = 'http://www.w3.org/2000/svg';

function el(tag, attrs = {}, parent) {
  const n = document.createElementNS(SVGNS, tag);
  for (const k in attrs) n.setAttribute(k, attrs[k]);
  if (parent) parent.appendChild(n);
  return n;
}
function h(tag, attrs = {}, html) {
  const n = document.createElement(tag);
  for (const k in attrs) {
    if (k === 'class') n.className = attrs[k];
    else n[k] = attrs[k];
  }
  if (html !== undefined) n.innerHTML = html;
  return n;
}
function hm(ts) {
  const d = new Date(ts * 1000);
  const p = (x) => String(x).padStart(2, '0');
  return p(d.getHours()) + ':' + p(d.getMinutes());
}
function fmtMin(sec) { return Math.round(sec / 60) + ' 分钟'; }
async function api(path, body) {
  const opt = body === undefined ? {} : {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body)
  };
  const r = await fetch(path, opt);
  if (!r.ok) throw new Error(await r.text());
  return r.json();
}
function toast(msg) {
  const t = document.getElementById('toast');
  t.textContent = msg;
  t.style.display = 'block';
  setTimeout(() => (t.style.display = 'none'), 2200);
}

const TC_COLORS = {
  'TC-01': '#6cb8ff', 'TC-02A': '#ffb86c', 'TC-02B': '#ff8f3d', 'TC-03': '#8dff9d'
};
const OVERALL_LABEL = {
  CONFORMING: '合格',
  CONFORMING_WITH_DEVIATION: '合格（含已接受偏差）',
  DEVIATION_PENDING: '偏差处理中',
  NON_CONFORMING: '不合格'
};

async function load() {
  STATE = await api('/api/state');
  render();
}

function render() {
  renderConclusion();
  renderSpec();
  renderPly();
  renderTcChart();
  renderEnv();
  renderRules();
  renderRuns();
}

function renderConclusion() {
  const e = STATE.evaluation;
  const c = document.getElementById('conclusion');
  c.className = 'conclusion ' + e.overall;
  c.textContent = OVERALL_LABEL[e.overall] || e.overall;
  document.getElementById('hash-line').textContent =
    '规范版本 ' + e.specVersion + ' ｜ 输入摘要 ' + e.inputHash;
}

function renderSpec() {
  const s = STATE.spec;
  const box = document.getElementById('spec-box');
  box.innerHTML = '';
  box.appendChild(h('div', {},
    `版本 <b>${s.version}</b>：保温带 <b>${s.band_low}–${s.band_high}°C（闭区间）</b>，` +
    `共同达标连续保温 ≥ <b>${s.min_soak_sec / 60} 分钟</b>，` +
    `升温 ≤ ${s.max_ramp_c_min}°C/min，压力 ≥ ${s.min_pressure_mpa} MPa，` +
    `真空 ≤ ${s.max_vacuum_kpa} kPa，必需热电偶 ${s.required_tc_count} 支。`));
  box.appendChild(h('div', { class: 'evidence' }, s.description));

  const bar = document.getElementById('stage-bar');
  bar.innerHTML = '';
  const svg = el('svg', { width: 1080, height: 56, viewBox: '0 0 1080 56' });
  const start = STATE.cycle.planned_start_ts;
  const end = STATE.cycle.planned_end_ts;
  const x = (ts) => 60 + (ts - start) / (end - start) * 960;
  const colors = { '升温': '#3f6fb0', '保温': '#b0833f', '降温': '#4f9d8f' };
  for (const st of STATE.stages) {
    el('rect', {
      x: x(st.start_ts), y: 14, width: x(st.end_ts) - x(st.start_ts), height: 26,
      fill: colors[st.name] || '#555', rx: 4
    }, svg);
    el('text', { x: (x(st.start_ts) + x(st.end_ts)) / 2, y: 32, fill: '#fff', 'text-anchor': 'middle', 'font-size': 13 }, svg)
      .textContent = st.name;
    el('text', { x: x(st.start_ts), y: 52, fill: '#8fa3c8', 'text-anchor': 'middle', 'font-size': 11 }, svg)
      .textContent = hm(st.start_ts);
  }
  el('text', { x: x(end), y: 52, fill: '#8fa3c8', 'text-anchor': 'middle', 'font-size': 11 }, svg)
    .textContent = hm(end);
  bar.appendChild(svg);
}

function renderPly() {
  const svg = document.getElementById('ply-svg');
  svg.innerHTML = '';
  const plies = STATE.plies;
  const n = plies.length;
  const h0 = 26, gap = 6;
  el('text', { x: 12, y: 18, fill: '#9db4e8', 'font-size': 12 }, svg)
    .textContent = '↑ 模具面（第 1 层）';
  plies.forEach((p, i) => {
    const y = 28 + i * (h0 + gap);
    const ang = ((p.orientation_deg % 180) + 180) % 180;
    const fill = ['#2a4a73', '#3b5a35', '#6e5a2a', '#5a2f55'][i % 4];
    el('rect', { x: 90, y, width: 340, height: h0, fill, stroke: '#6f86b8', 'stroke-width': 1 }, svg);
    const g = el('g', { transform: `translate(260 ${y + h0 / 2}) rotate(${ang})` }, svg);
    for (let k = -7; k <= 7; k++) {
      el('line', { x1: k * 26, y1: -h0 / 2, x2: k * 26, y2: h0 / 2, stroke: 'rgba(255,255,255,0.28)', 'stroke-width': 1 }, g);
    }
    el('text', { x: 100, y: y + 17, fill: '#fff', 'font-size': 12 }, svg)
      .textContent = '#' + p.seq;
    el('text', { x: 398, y: y + 17, fill: '#d8e0ee', 'font-size': 12 }, svg)
      .textContent = `${p.orientation_deg >= 0 ? '+' : ''}${p.orientation_deg}°`;
  });
  el('text', { x: 12, y: 28 + n * (h0 + gap) + 4, fill: '#9db4e8', 'font-size': 12 }, svg)
    .textContent = '↓ 真空袋面（第 ' + n + ' 层）';

  const tbl = document.getElementById('ply-table');
  let html = '<table><tr><th>层</th><th>材料</th><th>方向</th><th>批次/证书</th><th>铺放记录</th><th>时间</th><th>操作工</th></tr>';
  for (const p of plies) {
    html += `<tr><td>${p.seq}</td><td>${p.material_code}</td><td>${p.orientation_deg}°</td>` +
      `<td>${p.lot_code}<br><span class="evidence">${STATE.lot.cert_no}</span></td>` +
      `<td>${p.layup_serial}</td><td>${hm(p.laid_ts)}</td><td>${p.operator}</td></tr>`;
  }
  html += '</table>';
  tbl.innerHTML = html;
}

function chartScales() {
  const start = STATE.cycle.planned_start_ts;
  const end = STATE.cycle.planned_end_ts;
  return {
    start, end,
    X: (ts) => 60 + (ts - start) / (end - start) * 990,
    Y: (temp) => 380 - (temp - 30) / 180 * 330
  };
}

function selectedControlId() {
  const ctl = STATE.thermocouples.find((t) => t.is_control === 1);
  let id = ctl ? ctl.id : null;
  for (const a of STATE.actions) {
    if (a.type === 'SELECT_CONTROL') id = a.target;
  }
  return id;
}

function renderTcChart() {
  const svg = document.getElementById('tc-svg');
  svg.innerHTML = '';
  const sc = chartScales();
  const low = STATE.spec.band_low, high = STATE.spec.band_high;

  const hold = STATE.stages.find((s) => s.name === '保温');
  el('rect', { x: sc.X(hold.start_ts), y: 40, width: sc.X(hold.end_ts) - sc.X(hold.start_ts), height: 340, fill: 'rgba(176,131,63,0.08)' }, svg);

  el('rect', { x: 60, y: sc.Y(high), width: 990, height: sc.Y(low) - sc.Y(high), fill: 'rgba(63,185,122,0.12)', stroke: 'none' }, svg);
  el('line', { x1: 60, y1: sc.Y(high), x2: 1050, y2: sc.Y(high), stroke: '#3fb97a', 'stroke-dasharray': '4 3' }, svg);
  el('line', { x1: 60, y1: sc.Y(low), x2: 1050, y2: sc.Y(low), stroke: '#3fb97a', 'stroke-dasharray': '4 3' }, svg);
  el('text', { x: 64, y: sc.Y(high) - 4, fill: '#67e8a3', 'font-size': 11 }, svg).textContent = '上限 ' + high + '°C';
  el('text', { x: 64, y: sc.Y(low) + 13, fill: '#67e8a3', 'font-size': 11 }, svg).textContent = '下限 ' + low + '°C';

  for (let t = 30; t <= 210; t += 30) {
    el('line', { x1: 56, y1: sc.Y(t), x2: 1050, y2: sc.Y(t), stroke: '#22304a' }, svg);
    el('text', { x: 50, y: sc.Y(t) + 4, fill: '#7b8bb0', 'text-anchor': 'end', 'font-size': 10 }, svg).textContent = t;
  }
  for (let m = 0; m <= 370; m += 30) {
    const ts = sc.start + m * 60;
    el('line', { x1: sc.X(ts), y1: 40, x2: sc.X(ts), y2: 380, stroke: '#1c2740' }, svg);
    el('text', { x: sc.X(ts), y: 398, fill: '#7b8bb0', 'text-anchor': 'middle', 'font-size': 10 }, svg).textContent = hm(ts);
  }

  const byTc = {};
  for (const s of STATE.tcSamples) {
    (byTc[s.tc_id] = byTc[s.tc_id] || []).push(s);
  }
  for (const tcId of Object.keys(byTc).sort()) {
    const pts = byTc[tcId].map((s) => sc.X(s.ts) + ',' + sc.Y(s.temp)).join(' ');
    el('polyline', {
      points: pts, fill: 'none', stroke: TC_COLORS[tcId] || '#ccc',
      'stroke-width': tcId === selectedControlId() ? 2.6 : 1.6, opacity: 0.95
    }, svg);
  }

  for (const j of STATE.evaluation.jointRanges) {
    if (j.end < hold.start_ts || j.start > hold.end_ts) continue;
    const x1 = sc.X(Math.max(j.start, hold.start_ts));
    const x2 = sc.X(Math.min(j.end, hold.end_ts));
    el('rect', { x: x1, y: 44, width: x2 - x1, height: 12, fill: '#3fb97a', opacity: 0.85, rx: 2 }, svg);
  }

  for (const e of STATE.evaluation.integrityEvents) {
    const x = sc.X(e.ts);
    const color = e.type === 'SENSOR_REPLACEMENT' ? '#e8a067' : '#e05a75';
    el('line', { x1: x, y1: 40, x2: x, y2: 380, stroke: color, 'stroke-dasharray': '2 3', 'stroke-width': 1 }, svg);
    el('polygon', { points: `${x - 4},44 ${x + 4},44 ${x},52`, fill: color }, svg);
  }

  for (const a of STATE.evaluation.suspectedAnomalies) {
    el('circle', { cx: sc.X(a.startTs), cy: sc.Y(a.temp), r: 5, fill: 'none', stroke: '#ff5f6d', 'stroke-width': 2 }, svg);
  }

  renderTcControls(sc);
  renderSoakSummary();
}

function renderTcControls(sc) {
  const box = document.getElementById('tc-controls');
  const controlId = selectedControlId();
  let html = '<div class="legend">';
  for (const t of STATE.thermocouples) {
    html += `<span><i class="sw" style="background:${TC_COLORS[t.id]}"></i>${t.id} ${t.position_label}` +
      (t.id === controlId ? '（控制点）' : '') + '</span>';
  }
  html += '<span><i class="sw" style="background:#3fb97a"></i>共同达标证据段</span>';
  html += '<span><i class="sw" style="background:#e8a067"></i>传感器替换</span>';
  html += '<span><i class="sw" style="background:#e05a75"></i>时钟回退/重复样本</span>';
  html += '<span><i class="sw" style="background:none;border:1px solid #ff5f6d;border-radius:50%"></i>疑似异常</span>';
  html += '</div><div style="margin:6px 0">选择部件控制点： ';
  for (const t of STATE.thermocouples) {
    html += ` <button data-tc="${t.id}" class="ctl-btn">${t.id}</button>`;
  }
  html += '</div>';
  box.innerHTML = html;
  box.querySelectorAll('.ctl-btn').forEach((b) => b.onclick = async () => {
    await api('/api/actions/select-control', { tcId: b.dataset.tc, actor: '工艺员' });
    toast('控制点已切换为 ' + b.dataset.tc);
    load();
  });
}

function renderSoakSummary() {
  const e = STATE.evaluation;
  const box = document.getElementById('soak-summary');
  let html = `<div class="evidence">共同达标最长连续保温：<b>${Math.round(e.longestJointSec / 60)} 分钟</b>` +
    `（要求 ≥ 120 分钟）；共同达标累计 ${Math.round(e.jointTotalSec / 60)} 分钟（累计值不可用于连续保温判定）。</div>`;
  html += '<div class="evidence">证据区间：';
  for (const j of e.jointRanges) {
    html += `<span class="range-chip">${hm(j.start)}–${hm(j.end)}（${Math.round(j.seconds / 60)} 分钟，段 ${j.segmentKeys.join(' | ')}）</span>`;
  }
  html += '</div>';
  let ibox = '<div class="evidence" style="margin-top:6px">完整性事件：';
  for (const ev of e.integrityEvents) {
    ibox += `<span class="range-chip">${hm(ev.ts)} ${ev.type} ${ev.tcId}</span>`;
  }
  ibox += '</div>';
  ibox += '<div style="margin-top:8px">';
  for (const a of e.suspectedAnomalies) {
    ibox += `<div class="suspected">疑似传感器异常：${a.tcId} ${hm(a.startTs)}–${hm(a.endTs)} 峰值 ${a.temp}°C` +
      ` <button data-tc="${a.tcId}" data-s="${a.startTs}" data-e="${a.endTs}" data-temp="${a.temp}" class="confirm-btn">确认传感器异常</button></div>`;
  }
  ibox += '</div>';
  box.innerHTML = html;
  document.getElementById('integrity-box').innerHTML = ibox;
  box.parentElement.querySelectorAll('.confirm-btn').forEach((b) => b.onclick = async () => {
    await api('/api/actions/confirm-anomaly', {
      tcId: b.dataset.tc, startTs: +b.dataset.s, endTs: +b.dataset.e,
      note: '确认 ' + b.dataset.tc + ' 尖峰 ' + b.dataset.temp + '°C 为传感器异常', actor: '质量工程师'
    });
    toast('异常已确认，证据段按异常窗口剔除并重新评估');
    load();
  });
}

function renderEnv() {
  const svg = document.getElementById('env-svg');
  svg.innerHTML = '';
  const sc = chartScales();
  const groups = {};
  for (const s of STATE.envSamples) (groups[s.kind] = groups[s.kind] || []).push(s);
  const draw = (kind, color, yTop, yBot, minV, maxV, label, unit) => {
    const Y = (v) => yBot - (v - minV) / (maxV - minV) * (yBot - yTop);
    const pts = groups[kind].map((s) => sc.X(s.ts) + ',' + Y(s.value)).join(' ');
    el('polyline', { points: pts, fill: 'none', stroke: color, 'stroke-width': 1.8 }, svg);
    el('text', { x: 8, y: yTop + 4, fill: color, 'font-size': 11 }, svg).textContent = label;
    return Y;
  };
  const hold = STATE.stages.find((s) => s.name === '保温');
  el('rect', { x: sc.X(hold.start_ts), y: 14, width: sc.X(hold.end_ts) - sc.X(hold.start_ts), height: 130, fill: 'rgba(176,131,63,0.08)' }, svg);
  const yp = draw('pressure', '#6cb8ff', 20, 140, 0, 0.7);
  el('line', { x1: 60, y1: yp(STATE.spec.min_pressure_mpa), x2: 1050, y2: yp(STATE.spec.min_pressure_mpa), stroke: '#3fb97a', 'stroke-dasharray': '4 3' }, svg);
  el('text', { x: 900, y: yp(STATE.spec.min_pressure_mpa) - 4, fill: '#67e8a3', 'font-size': 10 }, svg)
    .textContent = '压力下限 ' + STATE.spec.min_pressure_mpa + ' MPa';
  const yv = draw('vacuum', '#c792ff', 160, 280, -100, 0);
  el('line', { x1: 60, y1: yv(STATE.spec.max_vacuum_kpa), x2: 1050, y2: yv(STATE.spec.max_vacuum_kpa), stroke: '#3fb97a', 'stroke-dasharray': '4 3' }, svg);
  el('text', { x: 880, y: yv(STATE.spec.max_vacuum_kpa) - 4, fill: '#67e8a3', 'font-size': 10 }, svg)
    .textContent = '真空上限 ' + STATE.spec.max_vacuum_kpa + ' kPa';
}

function deviationsByRule() {
  const m = {};
  for (const d of STATE.deviations) (m[d.rule_code] = m[d.rule_code] || []).push(d);
  return m;
}

function renderRules() {
  const box = document.getElementById('rules');
  box.innerHTML = '';
  const devMap = deviationsByRule();
  for (const r of STATE.evaluation.rules) {
    const devs = devMap[r.code] || [];
    const accepted = devs.some((d) => d.status === 'ACCEPTED');
    const pending = devs.some((d) => d.status === 'OPEN');
    const cls = r.pass ? 'pass' : 'fail';
    const div = h('div', { class: 'rule ' + cls });
    let badge = r.pass ? '<span class="badge pass">通过</span>'
      : accepted ? '<span class="badge mitigated">已接受偏差</span>'
      : pending ? '<span class="badge pending">偏差处理中</span>'
      : '<span class="badge fail">失败</span>';
    div.appendChild(h('h3', {}, badge + ' <span>' + r.code + ' · ' + r.title + '</span>'));
    div.appendChild(h('div', {}, r.message));
    let ranges = '';
    if (!r.pass && r.failRanges && r.failRanges.length) {
      ranges = '<div class="evidence" style="margin-top:4px">失败时间范围：' +
        r.failRanges.map((f) => `<span class="range-chip">${hm(f[0])}–${hm(f[1])}</span>`).join('') +
        '</div>';
    }
    div.insertAdjacentHTML('beforeend', ranges);
    div.appendChild(h('div', { class: 'evidence' }, summarizeEvidence(r.code)));

    for (const d of devs) {
      const statusLabel = { OPEN: '处理中', ACCEPTED: '已接受', REJECTED: '已驳回' }[d.status] || d.status;
      let html = `<div class="deviation">偏差分支 #${d.branch_no}（${statusLabel}）负责人：${d.owner}｜${d.reason || '—'}`;
      if (d.status === 'OPEN') {
        html += ` <button data-id="${d.id}" data-st="ACCEPTED" class="dev-btn">接受</button>` +
          ` <button data-id="${d.id}" data-st="REJECTED" class="dev-btn">驳回</button>`;
      }
      if (d.resolution) html += `<br><span class="evidence">结论：${d.resolution}</span>`;
      html += '</div>';
      div.insertAdjacentHTML('beforeend', html);
    }

    if (!r.pass && !accepted) {
      div.insertAdjacentHTML('beforeend',
        `<div style="margin-top:8px"><button class="new-dev" data-rule="${r.code}">建立偏差处理分支</button></div>`);
    }
    box.appendChild(div);
  }
  box.querySelectorAll('.dev-btn').forEach((b) => b.onclick = async () => {
    const resolution = b.dataset.st === 'ACCEPTED'
      ? prompt('接受依据（可附工程处置说明）', '按工艺让步接收，加强下炉次监控')
      : prompt('驳回理由', '不满足让步条件');
    if (resolution === null) return;
    await api('/api/deviations/resolve', { id: +b.dataset.id, status: b.dataset.st, resolution });
    toast('偏差分支已更新');
    load();
  });
  box.querySelectorAll('.new-dev').forEach((b) => b.onclick = async () => {
    const reason = prompt('偏差处理说明', '共同达标保温不足，申请让步评审');
    if (reason === null) return;
    await api('/api/deviations', { ruleCode: b.dataset.rule, owner: '质量工程师', reason });
    toast('偏差分支已建立');
    load();
  });
}

function summarizeEvidence(code) {
  const rows = STATE.latestRuleResults.filter((r) => r.rule_code === code);
  if (!rows.length) return '';
  const ev = JSON.parse(rows[0].evidence_json);
  if (code === 'R1-LAYUP') return `铺层 ${ev.plyCount} 层，材料方向序列与铺放记录逐行关联，操作工可追溯。`;
  if (code === 'R2-LOT') return `批次 ${ev.lotCode}，证书 ${ev.certNo}，收货 ${new Date(ev.receivedTs * 1000).toISOString().slice(0, 10)}，到期 ${new Date(ev.expiryTs * 1000).toISOString().slice(0, 10)}。`;
  if (code === 'R3-RAMP') return `最陡升温 ${ev.worstRateCMin}°C/min（限值 ${ev.maxRampCMin}）；控制点到温 ${ev.controlEntryLabel}；异常窗口 ${ev.confirmedAnomalyRanges.length} 段已剔除。`;
  if (code === 'R4-SOAK') return `保温窗 ${ev.holdStartLabel}–${ev.holdEndLabel}；最长共同连续 ${ev.longestJointMin} 分钟；各位置在带累计：` +
    Object.entries(ev.perPositionInBandMin).map(([k, v]) => `${k}=${Math.round(v / 60)} 分钟`).join('，') + '（仅逐支参考，不可相加）。';
  if (code === 'R5-PRESSURE') return `保温段最低 ${ev.extremeValue} MPa @${ev.extremeLabel}（限值 ${ev.threshold}），样本 ${ev.sampleCount} 条。`;
  if (code === 'R6-VACUUM') return `保温段最高 ${ev.extremeValue} kPa @${ev.extremeLabel}（限值 ${ev.threshold}），样本 ${ev.sampleCount} 条。`;
  if (code === 'R7-INTEGRITY') return `完整性事件 ${ev.events.length} 个：` + ev.events.map((e) => `${e.label} ${e.type}`).join('；');
  if (code === 'R8-COVERAGE') return ev.positions.map((p) => `${p.tcIds} 覆盖率 ${(p.ratio * 100).toFixed(1)}%`).join('；') + '。' + ev.replacementPolicy;
  return '';
}

function renderRuns() {
  const box = document.getElementById('runs');
  let html = '<table><tr><th>#</th><th>时间</th><th>结论</th><th>规范</th><th>输入摘要</th><th>最长共同保温</th><th>共同累计</th></tr>';
  for (const r of STATE.runs) {
    html += `<tr><td>${r.id}</td><td>${new Date(r.created_ts * 1000).toLocaleString()}</td>` +
      `<td>${OVERALL_LABEL[r.overall] || r.overall}</td><td>${r.spec_version}</td>` +
      `<td><code>${r.input_hash}</code></td><td>${Math.round(r.longest_joint_sec / 60)} 分钟</td>` +
      `<td>${Math.round(r.joint_total_sec / 60)} 分钟</td></tr>`;
  }
  html += '</table>';
  box.innerHTML = html;
}

document.getElementById('btn-replay').onclick = async () => {
  await api('/api/state');
  await load();
  toast('已按当前输入重放评估');
};
document.getElementById('btn-export').onclick = async () => {
  const dump = await api('/api/export');
  const blob = new Blob([JSON.stringify(dump, null, 2)], { type: 'application/json' });
  const a = document.createElement('a');
  a.href = URL.createObjectURL(blob);
  a.download = 'cure-evidence-export.json';
  a.click();
  toast('运行记录已导出');
};
document.getElementById('file-import').onchange = async (ev) => {
  const f = ev.target.files[0];
  if (!f) return;
  const text = await f.text();
  const r = await fetch('/api/import', {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: text
  });
  if (!r.ok) { toast('导入失败：' + (await r.text())); return; }
  toast('导入完成，已重放复核');
  load();
};
document.getElementById('btn-reset').onclick = async () => {
  if (!confirm('确认清空数据库并重新写入固定夹具？')) return;
  await api('/api/reset');
  toast('已清空并重置固定夹具');
  load();
};

load().catch((e) => {
  document.getElementById('conclusion').textContent = '加载失败：' + e.message;
});
