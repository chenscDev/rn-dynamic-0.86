#!/usr/bin/env node
/**
 * 分包打包管理后台（本地）
 * - 同一 RN 工程下可管理多个「分支项目」
 * - 每个项目有测试 / 线上环境配置
 * - SSE 实时构建日志（可收起展开）
 *
 * 启动：yarn pack:admin
 * 打开：http://127.0.0.1:8790
 */
'use strict';

const http = require('http');
const fs = require('fs');
const path = require('path');
const { spawn, spawnSync } = require('child_process');
const { URL } = require('url');
const crypto = require('crypto');

const PORT = Number(process.env.PACK_ADMIN_PORT || 8790);
const ROOT = path.resolve(__dirname, '../..');
const PUBLIC_DIR = path.join(__dirname, 'public');
const PROJECTS_PATH = path.join(__dirname, 'config', 'projects.json');
const LEGACY_ENV_PATH = path.join(__dirname, 'config', 'environments.json');
const UPLOAD_CONFIG_PATH = path.join(ROOT, 'project/config/upload.local.json');

/** @type {Map<string, any>} */
const jobs = new Map();

function readJson(filePath, fallback) {
  try {
    return JSON.parse(fs.readFileSync(filePath, 'utf8'));
  } catch {
    return fallback;
  }
}

function writeJson(filePath, data) {
  fs.mkdirSync(path.dirname(filePath), { recursive: true });
  fs.writeFileSync(filePath, JSON.stringify(data, null, 2) + '\n');
}

function normalizeChannel(raw) {
  const trimmed = String(raw || '')
    .trim()
    .toLowerCase()
    .replace(/\\/g, '/')
    .replace(/^refs\/heads\//, '');
  if (!trimmed) return 'master';
  return (
    trimmed
      .replace(/[^a-z0-9._/-]+/g, '-')
      .replace(/\//g, '-')
      .replace(/-+/g, '-')
      .replace(/^-|-$/g, '') || 'master'
  );
}

function loadProjectsDoc() {
  const doc = readJson(PROJECTS_PATH, null);
  if (doc && Array.isArray(doc.projects)) {
    return doc;
  }
  // 兼容旧 environments.json → 迁移为单项目
  const legacy = readJson(LEGACY_ENV_PATH, null);
  if (legacy && typeof legacy === 'object') {
    return {
      projects: [
        {
          id: 'default',
          name: '默认项目',
          branch: 'master',
          rnVersion: '0.86.0',
          rnBizRoot: '../rn-biz-0.86',
          enabled: true,
          environments: legacy,
        },
      ],
    };
  }
  return { projects: [] };
}

function saveProjectsDoc(doc) {
  writeJson(PROJECTS_PATH, doc);
}

function listGitBranches(repoRoot) {
  const result = spawnSync(
    'git',
    ['for-each-ref', '--format=%(refname:short)', 'refs/heads'],
    { cwd: repoRoot, encoding: 'utf8' },
  );
  if (result.status !== 0) return [];
  return String(result.stdout || '')
    .split('\n')
    .map(s => s.trim())
    .filter(Boolean);
}

function currentBranch(repoRoot) {
  const result = spawnSync('git', ['rev-parse', '--abbrev-ref', 'HEAD'], {
    cwd: repoRoot,
    encoding: 'utf8',
  });
  if (result.status !== 0) return 'master';
  const branch = String(result.stdout || '').trim();
  return branch && branch !== 'HEAD' ? branch : 'master';
}

function sendJson(res, status, data) {
  res.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Cache-Control': 'no-store',
  });
  res.end(JSON.stringify(data));
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    req.on('data', c => chunks.push(c));
    req.on('end', () => {
      try {
        const raw = Buffer.concat(chunks).toString('utf8');
        resolve(raw ? JSON.parse(raw) : {});
      } catch (error) {
        reject(error);
      }
    });
    req.on('error', reject);
  });
}

function appendJobLog(job, line) {
  const text = String(line).replace(/\r/g, '');
  if (!text) return;
  job.logs.push(text);
  if (job.logs.length > 4000) job.logs.splice(0, job.logs.length - 4000);
  for (const res of job.listeners) {
    res.write(`data: ${JSON.stringify({ type: 'log', line: text })}\n\n`);
  }
}

function finishJob(job, status) {
  job.status = status;
  job.finishedAt = Date.now();
  for (const res of job.listeners) {
    res.write(
      `data: ${JSON.stringify({ type: 'done', status, finishedAt: job.finishedAt })}\n\n`,
    );
    res.end();
  }
  job.listeners.clear();
}

function runCommand(job, command, args, options = {}) {
  return new Promise((resolve, reject) => {
    appendJobLog(job, `$ ${command} ${args.join(' ')}`);
    const child = spawn(command, args, {
      cwd: options.cwd || ROOT,
      env: { ...process.env, ...options.env },
      shell: false,
    });
    child.stdout.on('data', buf => {
      String(buf).split('\n').forEach(line => appendJobLog(job, line));
    });
    child.stderr.on('data', buf => {
      String(buf).split('\n').forEach(line => appendJobLog(job, line));
    });
    child.on('error', reject);
    child.on('close', code => {
      if (code === 0) resolve();
      else reject(new Error(`${command} 退出码 ${code}`));
    });
  });
}

async function runBuildJob(job) {
  const { project, envKey, envConfig, actions, branch } = job.meta;
  const channel = normalizeChannel(branch || project.branch);
  const rnBizRoot = path.resolve(ROOT, project.rnBizRoot || '../rn-biz-0.86');
  const platforms = envConfig.platforms || ['android'];
  const pageKeys = envConfig.pageKeys || ['login', 'home'];

  appendJobLog(job, `==> 项目=${project.name}(${project.id}) env=${envKey}`);
  appendJobLog(job, `==> branch=${branch} channel=${channel} rnVersion=${project.rnVersion || '0.86.0'}`);
  appendJobLog(job, `==> rn-dynamic: ${ROOT}`);
  appendJobLog(job, `==> rn-biz: ${rnBizRoot}`);

  const uploadConfig = readJson(UPLOAD_CONFIG_PATH, {});
  uploadConfig.provider = envConfig.provider || uploadConfig.provider || 'local';
  uploadConfig.baseUrl = envConfig.baseUrl || uploadConfig.baseUrl;
  uploadConfig.defaultChannel = channel;
  if (envConfig.cdn) {
    uploadConfig.cdn = { ...(uploadConfig.cdn || {}), ...envConfig.cdn };
  }
  writeJson(UPLOAD_CONFIG_PATH, uploadConfig);
  appendJobLog(job, `==> 已同步 upload.local.json provider=${uploadConfig.provider}`);

  const env = {
    RN_PACK_CHANNEL: channel,
    CDN_BASE_URL: envConfig.baseUrl || '',
  };

  if (actions.includes('pack')) {
    for (const platform of platforms) {
      appendJobLog(job, `==> 打包 common @${platform}`);
      await runCommand(
        job,
        'yarn',
        ['pack:build:common', '--platform', platform, '--channel', channel],
        { cwd: rnBizRoot, env },
      );
      for (const key of pageKeys) {
        appendJobLog(job, `==> 打包 ${key} @${platform}`);
        await runCommand(
          job,
          'yarn',
          ['pack:build', key, '--platform', platform, '--channel', channel],
          { cwd: rnBizRoot, env },
        );
      }
    }
  }

  if (actions.includes('publish') && envConfig.publish !== false) {
    for (const platform of platforms) {
      await runCommand(
        job,
        'yarn',
        [
          'pack:publish:common',
          '--platform',
          platform,
          '--channel',
          channel,
          '--provider',
          envConfig.provider || 'local',
        ],
        { cwd: rnBizRoot, env },
      );
      for (const key of pageKeys) {
        await runCommand(
          job,
          'yarn',
          [
            'pack:publish',
            key,
            '--platform',
            platform,
            '--channel',
            channel,
            '--provider',
            envConfig.provider || 'local',
          ],
          { cwd: rnBizRoot, env },
        );
      }
    }
  }

  if (actions.includes('embed') || actions.includes('apk')) {
    appendJobLog(job, '==> 嵌入分包到 Android assets');
    await runCommand(job, 'bash', ['scripts/embed-login-bundles.sh'], {
      cwd: ROOT,
      env,
    });
  }

  if (actions.includes('apk') && envConfig.buildApk !== false) {
    appendJobLog(job, `==> 构建 APK packageName=${envConfig.packageName || 'RnDynamicBase'}`);
    await runCommand(job, 'bash', ['scripts/build-pgyer-apk.sh'], {
      cwd: ROOT,
      env,
    });
    const apkPath = path.join(ROOT, 'project/dist/apk/RnDynamicBase-internal-release.apk');
    if (fs.existsSync(apkPath) && envConfig.packageName) {
      const named = path.join(
        ROOT,
        'project/dist/apk',
        `${envConfig.packageName}-internal-release.apk`,
      );
      fs.copyFileSync(apkPath, named);
      appendJobLog(job, `==> 已复制命名 APK: ${named}`);
    }
  }

  appendJobLog(job, '==> 任务完成');
}

function serveStatic(req, res, pathname) {
  const safePath = pathname === '/' ? '/index.html' : pathname;
  const filePath = path.normalize(path.join(PUBLIC_DIR, safePath));
  if (!filePath.startsWith(PUBLIC_DIR)) {
    res.writeHead(403);
    res.end('Forbidden');
    return;
  }
  if (!fs.existsSync(filePath) || fs.statSync(filePath).isDirectory()) {
    res.writeHead(404);
    res.end('Not Found');
    return;
  }
  const ext = path.extname(filePath);
  const types = {
    '.html': 'text/html; charset=utf-8',
    '.js': 'application/javascript; charset=utf-8',
    '.css': 'text/css; charset=utf-8',
    '.json': 'application/json; charset=utf-8',
  };
  res.writeHead(200, { 'Content-Type': types[ext] || 'application/octet-stream' });
  fs.createReadStream(filePath).pipe(res);
}

function defaultEnvPair() {
  return {
    test: {
      label: '测试环境',
      provider: 'local',
      baseUrl: 'http://127.0.0.1:8787',
      packageName: 'RnDynamicBase-test',
      platforms: ['android'],
      pageKeys: ['login', 'home', 'order', 'demo', 'profile', 'wallet', 'message'],
      buildApk: true,
      publish: true,
      cdn: { endpoint: '', bucket: '', region: '', comment: '' },
    },
    production: {
      label: '线上环境',
      provider: 'cdn',
      baseUrl: 'https://cdn.rn.example.com',
      packageName: 'RnDynamicBase',
      platforms: ['android'],
      pageKeys: ['login', 'home', 'order', 'demo', 'profile', 'wallet', 'message'],
      buildApk: true,
      publish: true,
      cdn: {
        endpoint: 'oss-cn-hangzhou.aliyuncs.com',
        bucket: 'rn-bundles',
        region: 'oss-cn-hangzhou',
        comment: '',
      },
    },
  };
}

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url || '/', `http://${req.headers.host || 'localhost'}`);
  const { pathname } = url;

  try {
    if (req.method === 'GET' && pathname === '/api/meta') {
      const doc = loadProjectsDoc();
      const sampleBiz = path.resolve(
        ROOT,
        doc.projects[0]?.rnBizRoot || '../rn-biz-0.86',
      );
      return sendJson(res, 200, {
        hostRoot: ROOT,
        hostBranch: currentBranch(ROOT),
        hostBranches: listGitBranches(ROOT),
        bizRoot: sampleBiz,
        bizBranch: fs.existsSync(sampleBiz) ? currentBranch(sampleBiz) : null,
        bizBranches: fs.existsSync(sampleBiz) ? listGitBranches(sampleBiz) : [],
        projects: doc.projects,
        uploadConfig: readJson(UPLOAD_CONFIG_PATH, {}),
      });
    }

    if (req.method === 'GET' && pathname === '/api/projects') {
      return sendJson(res, 200, loadProjectsDoc());
    }

    if (req.method === 'PUT' && pathname === '/api/projects') {
      const body = await readBody(req);
      if (!body || !Array.isArray(body.projects)) {
        return sendJson(res, 400, { error: '需要 { projects: [] }' });
      }
      saveProjectsDoc({ projects: body.projects });
      return sendJson(res, 200, { ok: true });
    }

    if (req.method === 'POST' && pathname === '/api/projects') {
      const body = await readBody(req);
      const doc = loadProjectsDoc();
      const branch = String(body.branch || '').trim();
      if (!branch) return sendJson(res, 400, { error: '请填写分支名' });
      const id = normalizeChannel(body.id || branch);
      if (doc.projects.some(p => p.id === id)) {
        return sendJson(res, 400, { error: `项目已存在: ${id}` });
      }
      const project = {
        id,
        name: body.name || `${branch} 项目`,
        branch,
        rnVersion: body.rnVersion || '0.86.0',
        rnBizRoot: body.rnBizRoot || '../rn-biz-0.86',
        enabled: body.enabled !== false,
        environments: body.environments || defaultEnvPair(),
      };
      // 默认包名带上项目 id，避免产物覆盖
      if (project.environments.test) {
        project.environments.test.packageName =
          project.environments.test.packageName || `RnDynamicBase-${id}-test`;
      }
      if (project.environments.production) {
        project.environments.production.packageName =
          project.environments.production.packageName || `RnDynamicBase-${id}`;
      }
      doc.projects.push(project);
      saveProjectsDoc(doc);
      return sendJson(res, 200, { ok: true, project });
    }

    if (req.method === 'DELETE' && pathname.startsWith('/api/projects/')) {
      const id = decodeURIComponent(pathname.split('/')[3] || '');
      const doc = loadProjectsDoc();
      doc.projects = doc.projects.filter(p => p.id !== id);
      saveProjectsDoc(doc);
      return sendJson(res, 200, { ok: true });
    }

    if (req.method === 'POST' && pathname === '/api/jobs') {
      const body = await readBody(req);
      const projectId = String(body.projectId || '').trim();
      const envKey = String(body.env || 'test');
      const actions =
        Array.isArray(body.actions) && body.actions.length
          ? body.actions
          : ['pack', 'embed', 'apk'];
      const doc = loadProjectsDoc();
      const project = doc.projects.find(p => p.id === projectId);
      if (!project) return sendJson(res, 400, { error: `未知项目: ${projectId}` });
      if (project.enabled === false) {
        return sendJson(res, 400, { error: '项目已禁用' });
      }
      const envConfig = project.environments?.[envKey];
      if (!envConfig) return sendJson(res, 400, { error: `未知环境: ${envKey}` });
      const branch = String(body.branch || project.branch || '').trim();
      if (!branch) return sendJson(res, 400, { error: '请选择分支' });

      const id = crypto.randomBytes(6).toString('hex');
      const job = {
        id,
        status: 'running',
        logs: [],
        listeners: new Set(),
        startedAt: Date.now(),
        meta: {
          project,
          envKey,
          envConfig,
          actions,
          branch,
          channel: normalizeChannel(branch),
          packageName: envConfig.packageName,
        },
      };
      jobs.set(id, job);
      runBuildJob(job)
        .then(() => finishJob(job, 'success'))
        .catch(error => {
          appendJobLog(job, `!! 失败: ${error.message || error}`);
          finishJob(job, 'failed');
        });
      return sendJson(res, 200, { id, channel: job.meta.channel });
    }

    if (req.method === 'GET' && pathname.startsWith('/api/jobs/') && pathname.endsWith('/stream')) {
      const id = pathname.split('/')[3];
      const job = jobs.get(id);
      if (!job) {
        res.writeHead(404);
        res.end('job not found');
        return;
      }
      res.writeHead(200, {
        'Content-Type': 'text/event-stream; charset=utf-8',
        'Cache-Control': 'no-cache, no-transform',
        Connection: 'keep-alive',
      });
      res.write(
        `data: ${JSON.stringify({ type: 'hello', status: job.status, meta: job.meta })}\n\n`,
      );
      for (const line of job.logs) {
        res.write(`data: ${JSON.stringify({ type: 'log', line })}\n\n`);
      }
      if (job.status !== 'running') {
        res.write(
          `data: ${JSON.stringify({ type: 'done', status: job.status, finishedAt: job.finishedAt })}\n\n`,
        );
        res.end();
        return;
      }
      job.listeners.add(res);
      req.on('close', () => job.listeners.delete(res));
      return;
    }

    return serveStatic(req, res, pathname);
  } catch (error) {
    sendJson(res, 500, { error: error.message || String(error) });
  }
});

server.listen(PORT, '127.0.0.1', () => {
  console.log(`[pack-admin] http://127.0.0.1:${PORT}`);
  console.log(`[pack-admin] projects: ${PROJECTS_PATH}`);
});
