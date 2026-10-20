package com.cloudnest;

import static org.junit.jupiter.api.Assertions.*;

import com.cloudnest.auth.AccountMailTemplates;
import com.cloudnest.auth.AccountMailTemplates.Language;
import org.junit.jupiter.api.Test;

class AccountMailTemplatesTest {
  @Test
  void negotiatesOnlySupportedLanguagesWithSafeChineseDefault() {
    assertEquals(Language.EN_US, AccountMailTemplates.language("en-GB,en;q=0.9,zh-CN;q=0.5"));
    assertEquals(Language.ZH_CN, AccountMailTemplates.language("zh-CN,en;q=0.8"));
    assertEquals(Language.EN_US, AccountMailTemplates.language("fr-FR,en-US;q=0.8"));
    assertEquals(Language.ZH_CN, AccountMailTemplates.language("en-US;q=0,zh-CN;q=1"));
    for (String invalid : new String[] {null, "", "*", "fr-FR", "bad;q=oops", "en".repeat(300)}) {
      assertEquals(Language.ZH_CN, AccountMailTemplates.language(invalid));
    }
  }

  @Test
  void templatesKeepExpiryAndLinksAndDoNotRevealAccountExistence() {
    String link = "https://example.test/login?reset=fixture-token";
    var english = Language.EN_US.reset(link);
    assertTrue(english.subject().contains("reset your password"));
    assertTrue(english.body().contains("15 minutes"));
    assertTrue(english.body().endsWith(link));
    assertTrue(english.body().contains("ignore this message"));
    assertTrue(Language.ZH_CN.reset(link).body().contains("15 分钟"));
    assertTrue(Language.EN_US.verification(link).body().contains("verify your email"));
    assertTrue(Language.ZH_CN.verification(link).body().endsWith(link));
    assertTrue(Language.EN_US.acknowledgement().startsWith("If the account"));
  }
}
