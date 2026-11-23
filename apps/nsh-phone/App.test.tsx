import { expect, open, test } from "nsh/test";

test("boots", async () => {
  const app = await open("./App.tsx");
  expect(app.texts().length).toBeGreaterThan(0);
});
