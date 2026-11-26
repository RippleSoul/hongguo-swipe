import cn.local.hongguoswipe.ReturnPrompt;
import cn.local.hongguoswipe.SkipGate;

public final class ReturnPromptTest {
    private static void check(boolean expected, boolean actual, String message) {
        if (expected != actual) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        check(true, ReturnPrompt.text("上滑继续看短剧"), "Observed return prompt");
        check(true, ReturnPrompt.text("上滑\u200b继续 观看剧情"), "Whitespace and invisible separators");
        check(true, ReturnPrompt.text("向上滑动继续看短剧"), "Equivalent explicit prompt");
        check(false, ReturnPrompt.text("上滑查看更多商品"), "A product hint is not a return prompt");
        check(false, ReturnPrompt.text("继续看短剧"), "No gesture instruction");
        check(false, ReturnPrompt.text("有人说上滑继续看短剧"), "Mentioning instructions in a comment is insufficient");
        check(false, ReturnPrompt.text("5秒后上滑继续看短剧"), "A countdown is not a ready prompt");
        check(false, ReturnPrompt.text("上滑继续观看"), "Unqualified video hint is insufficient");
        check(false, ReturnPrompt.text(null), "Missing text");
        check(true, ReturnPrompt.liveBar(1240,2772,0,0,1240,2576,755,2681,806,2738), "Observed live preview with return strip");
        check(true, ReturnPrompt.liveBar(620,1386,0,0,620,1288,377,1340,403,1369), "Same geometry at another density");
        check(false, ReturnPrompt.liveBar(1240,2772,0,0,0,0,755,2681,806,2738), "An arrow without live preview is insufficient");
        check(false, ReturnPrompt.liveBar(1240,2772,0,0,1240,2772,755,2681,806,2738), "Full-screen video has no return strip");
        check(false, ReturnPrompt.liveBar(1240,2772,0,0,1240,2576,1113,172,1189,248), "Top menu icon is insufficient");
        check(false, ReturnPrompt.liveBar(1240,2772,0,0,1240,2576,300,2680,940,2710), "A wide navigation indicator is insufficient");
        check(false, ReturnPrompt.liveBar(2772,1240,0,0,2772,1000,1500,1100,1530,1130), "Do not guess unknown landscape layout");
        SkipGate gate = new SkipGate();
        check(false, gate.observe(0, true, true, 0), "Return prompt requires fresh confirmation");
        check(true, gate.observe(400, true, true, 0), "Stable positively identified return page can swipe without ad text");
        gate.attempted(400);
        check(false, gate.observe(800, true, true, 0), "Return retries are throttled");
        check(false, gate.observe(2400, false, true, 0), "Disappeared return prompt cannot swipe");
        System.out.println("PASS: explicit return text, observed live layout, negative layouts, stable evidence and retry guards");
    }
}
