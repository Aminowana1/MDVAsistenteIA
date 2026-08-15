package me.kev.sva.integrations;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class ProfileQueryTest {

  @Test
  void detectsRaceAndRpgLevel() {
    ProfileQuery query = ProfileQuery.from("iso que raza soy y que nivel tengo?");
    assertTrue(query.identity());
    assertTrue(query.any());
  }

  @Test
  void detectsTitleAndProfession() {
    ProfileQuery query = ProfileQuery.from("que titulo tengo y cual es mi nivel de mineria?");
    assertTrue(query.title());
    assertTrue(query.professions());
  }

  @Test
  void heldItemQuestionDoesNotWasteProfileContext() {
    ProfileQuery query = ProfileQuery.from("iso que es lo que tengo en la mano?");
    assertFalse(query.any());
  }

  @Test
  void detectsResourcesAndPoints() {
    ProfileQuery query = ProfileQuery.from("cuanto mana y cuantos puntos de atributo tengo?");
    assertTrue(query.resources());
    assertTrue(query.points());
  }
}
