export default async function globalTeardown(): Promise<void> {
  const child = globalThis.__MEAL_PG__;
  if (!child) return;
  await new Promise<void>((resolve) => {
    const timer = setTimeout(() => {
      child.kill('SIGKILL');
      resolve();
    }, 15_000);
    child.once('exit', () => {
      clearTimeout(timer);
      resolve();
    });
    // The helper stops PostgreSQL and removes its temp directory when stdin closes.
    child.stdin?.end();
  });
}
