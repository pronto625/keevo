package com.keevo.admin.campaign.application;

import com.keevo.admin.campaign.domain.model.AudienceMember;
import com.keevo.admin.campaign.domain.model.Channel;
import com.keevo.admin.campaign.domain.model.PlatformCampaign;
import com.keevo.admin.campaign.domain.model.PlatformCampaignStatus;
import com.keevo.admin.campaign.domain.model.WhatsAppContent;
import com.keevo.shared.infrastructure.web.InvalidRequestParameterException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Platform campaign rules: channels, readiness, audience per channel, variables (21.6)")
class PlatformCampaignRulesTest {

    private static PlatformCampaign campaign(List<Channel> channels, String title, String message, WhatsAppContent w) {
        return new PlatformCampaign(UUID.randomUUID(), "C", null, PlatformCampaignStatus.DRAFT, channels, title,
                message, null, w, null, null, null, 0, null, Instant.now(), Instant.now());
    }

    @Test
    @DisplayName("channels: names in any case, unknown refused")
    void channels() {
        assertThat(PlatformCampaignService.channels(List.of("push", "EMAIL", "push")))
                .containsExactly(Channel.PUSH, Channel.EMAIL);
        assertThatThrownBy(() -> PlatformCampaignService.channels(List.of("fax")))
                .isInstanceOf(InvalidRequestParameterException.class);
    }

    @Test
    @DisplayName("readiness: a channel, a message (not for WhatsApp alone), a title for push / email, a template, its media")
    void readiness() {
        assertThat(PlatformCampaignService.readiness(campaign(List.of(), "t", "m", null))).contains("canal");
        assertThat(PlatformCampaignService.readiness(campaign(List.of(Channel.SMS), null, null, null)))
                .contains("message");
        assertThat(PlatformCampaignService.readiness(campaign(List.of(Channel.EMAIL), null, "m", null)))
                .contains("titre");
        assertThat(PlatformCampaignService.readiness(campaign(List.of(Channel.WHATSAPP), null, null, null)))
                .contains("modèle");
        WhatsAppContent image = new WhatsAppContent("a", "fr", "x", "IMAGE", null, null, List.of());
        assertThat(PlatformCampaignService.readiness(campaign(List.of(Channel.WHATSAPP), null, null, image)))
                .contains("en-tête");
        WhatsAppContent ok = new WhatsAppContent("a", "fr", "x", "NONE", null, null, List.of());
        assertThat(PlatformCampaignService.readiness(campaign(List.of(Channel.WHATSAPP), null, null, ok))).isNull();
    }

    @Test
    @DisplayName("audience per channel: push for all, WhatsApp / SMS with a phone, email with an address")
    void split() {
        AudienceMember withAll = new AudienceMember(UUID.randomUUID(), UUID.randomUUID(), "kv_a", "A", "B", "Co",
                "+237690000001", "a@b.cm");
        AudienceMember noContact = new AudienceMember(UUID.randomUUID(), UUID.randomUUID(), "kv_b", null, null, "Co",
                null, null);
        var byChannel = PlatformCampaignService.split(List.of(withAll, noContact), List.of(Channel.values()));
        assertThat(byChannel.get(Channel.PUSH)).hasSize(2);
        assertThat(byChannel.get(Channel.WHATSAPP)).hasSize(1);
        assertThat(byChannel.get(Channel.SMS)).hasSize(1);
        assertThat(byChannel.get(Channel.EMAIL)).hasSize(1);
    }

    @Test
    @DisplayName("variables: Bref sources mapped, values resolved with fallback, count of distinct template variables")
    void variables() {
        assertThat(PlatformCampaignService.source("firstName")).isEqualTo("FIRST_NAME");
        assertThat(PlatformCampaignService.source("shopName")).isEqualTo("COMPANY_NAME");
        assertThatThrownBy(() -> PlatformCampaignService.source("phone"))
                .isInstanceOf(InvalidRequestParameterException.class);
        assertThat(PlatformCampaignService.variableCount("Bonjour {{1}} {{2}} {{1}}")).isEqualTo(2);
        assertThat(new WhatsAppContent.Variable("FULL_NAME", null).resolve("Awa", "Ngono", "Co"))
                .isEqualTo("Awa Ngono");
        assertThat(new WhatsAppContent.Variable("COMPANY_NAME", "votre entreprise").resolve("Awa", null, null))
                .isEqualTo("votre entreprise");
        assertThat(new WhatsAppContent.Variable("FIRST_NAME", null).resolve(null, null, null)).isEqualTo("-");
    }
}
