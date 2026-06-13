import { stopJob } from "@/lib/jobs";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

interface StopBody {
  jobId?: string;
  session?: string;
}

/** Explicitly kill a running job (the Stop button). */
export async function POST(req: Request) {
  const body = (await req.json().catch(() => ({}))) as StopBody;
  const ok = stopJob({ jobId: body.jobId, sessionId: body.session });
  return Response.json({ ok });
}
