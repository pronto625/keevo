package com.keevo.whatsappcloud.campaign.application;

import com.keevo.whatsappcloud.campaign.domain.model.RecipientStatus;
import com.keevo.whatsappcloud.campaign.domain.model.VariableMapping;
import com.keevo.whatsappcloud.campaign.domain.model.VariableSource;
import com.keevo.whatsappcloud.inbox.domain.model.StatusChange;
import com.keevo.shared.infrastructure.web.InvalidRequestParameterException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Client campaign rules: variables, recipient labels, status deltas (21.6)")
class CampaignRulesTest {

    @Test
    @DisplayName("variable sources accept Bref's spellings; a fixed text is required; values fall back to « - »")
    void variables() {
        assertThat(VariableSource.parse("customerName")).contains(VariableSource.CLIENT_NAME);
        assertThat(VariableSource.parse("shopName")).contains(VariableSource.STORE_NAME);
        assertThat(VariableSource.parse("STATIC")).contains(VariableSource.STATIC);
        assertThat(VariableSource.parse("phone")).isEmpty();
        List<VariableMapping> v = CampaignService.variables(List.of(new CampaignService.VariableInput("clientName",
                "cher client"), new CampaignService.VariableInput("storeName", null)));
        assertThat(v.get(0).resolve("Awa", "Bref")).isEqualTo("Awa");
        assertThat(v.get(0).resolve(" ", "Bref")).isEqualTo("cher client");
        assertThat(v.get(1).resolve("Awa", null)).isEqualTo("-");
        assertThatThrownBy(() -> CampaignService.variables(List.of(new CampaignService.VariableInput("static", ""))))
                .isInstanceOf(InvalidRequestParameterException.class);
        assertThatThrownBy(() -> CampaignService.variables(List.of(new CampaignService.VariableInput("x", "y"))))
                .isInstanceOf(InvalidRequestParameterException.class);
    }

    @Test
    @DisplayName("recipient labels in French, with Meta's delivery of a sent message")
    void labels() {
        assertThat(RecipientStatus.label("SENT", "READ")).isEqualTo("Lu");
        assertThat(RecipientStatus.label("SENT", "DELIVERED")).isEqualTo("Délivré");
        assertThat(RecipientStatus.label("SENT", null)).isEqualTo("Envoyé");
        assertThat(RecipientStatus.label("SKIPPED", null)).isEqualTo("Non envoyé");
        assertThat(RecipientStatus.label("UNCERTAIN", null)).isEqualTo("Envoi incertain");
    }

    @Test
    @DisplayName("status changes move the counters by difference (order independent)")
    void deltas() {
        StatusChange delivered = new StatusChange(null, "SENT", "DELIVERED");
        assertThat(delivered.sentDelta()).isZero();
        assertThat(delivered.deliveredDelta()).isEqualTo(1);
        StatusChange read = new StatusChange(null, "SENT", "READ");
        assertThat(read.readDelta()).isEqualTo(1);
        assertThat(read.deliveredDelta()).isEqualTo(1);
        StatusChange failed = new StatusChange(null, "SENT", "FAILED");
        assertThat(failed.sentDelta()).isEqualTo(-1);
        assertThat(failed.failedDelta()).isEqualTo(1);
    }
}
