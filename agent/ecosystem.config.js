// pm2 — 맥마다 하나. 127.0.0.1 에만 바인딩하고, 폰에서 닿는 HTTPS 는 tailscale serve 같은 것으로 따로 연다.
//   CMR_MACHINE=home CMR_SEND=off pm2 start agent/ecosystem.config.js --update-env
const path = require('path');
const DIR = __dirname;
module.exports = {
  apps: [{
    name: 'cc-history-agent',
    cwd: DIR,
    script: path.join(DIR, '.venv/bin/python'),
    args: `-m uvicorn server:app --app-dir ${DIR} --host 127.0.0.1 --port 7797`,
    interpreter: 'none',
    autorestart: true,
    max_memory_restart: '512M',
    env: {
      PYTHONUNBUFFERED: '1',
      CMR_MACHINE: process.env.CMR_MACHINE || '',
      CMR_SEND: process.env.CMR_SEND || '',
    },
    out_file: path.join(DIR, 'data/pm2-out.log'),
    error_file: path.join(DIR, 'data/pm2-err.log'),
  }],
};
