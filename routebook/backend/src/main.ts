import pg from 'pg';
import { loadConfig } from './config.js';
import { PgStore } from './store.js';
import { createServers } from './server.js';
async function main() {
  const path=process.env.ROUTEBOOK_CONFIG;if(!path)throw Error('ROUTEBOOK_CONFIG required');
  const config=await loadConfig(path);
  const pool=new pg.Pool({connectionString:config.database_url,max:8,connectionTimeoutMillis:5000,idleTimeoutMillis:30000,statement_timeout:10000});
  pool.on('error',()=>{process.stderr.write('Database connection unavailable\n');});
  const store=new PgStore(pool,config.derivation);
  await store.assertDurability();
  if(process.argv.includes('--bootstrap')){try{await store.bootstrap(config.devices.map(d=>d.device_id));}finally{await pool.end();}return;}
  await store.health();
  const {publicApp,privateApp}=createServers(store,config);
  await publicApp.listen(config.public);await privateApp.listen(config.private);
  process.stdout.write('Routebook listeners started\n');
  const close=async()=>{await Promise.all([publicApp.close(),privateApp.close()]);await pool.end();};
  process.once('SIGTERM',()=>{void close();});process.once('SIGINT',()=>{void close();});
}
main().catch(()=>{process.stderr.write('Routebook startup failed; check private config/database\n');process.exitCode=1;});
