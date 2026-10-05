package forge.gamesimulationtests.comprehensiverules;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.game.phase.PhaseType;
import forge.gamesimulationtests.BaseGameSimulationTest;
import forge.gamesimulationtests.util.GameWrapper;
import forge.gamesimulationtests.util.card.CardSpecificationBuilder;
import forge.gamesimulationtests.util.card.CardSpecificationHandler;
import forge.gamesimulationtests.util.gamestate.GameStateSpecificationBuilder;
import forge.gamesimulationtests.util.player.PlayerSpecification;
import forge.gamesimulationtests.util.player.PlayerSpecificationBuilder;
import forge.gamesimulationtests.util.playeractions.ActionPreCondition;
import forge.gamesimulationtests.util.playeractions.ActivateAbilityAction;
import forge.gamesimulationtests.util.playeractions.CastSpellFromHandAction;
import forge.gamesimulationtests.util.playeractions.DeclareAttackersAction;
import forge.gamesimulationtests.util.playeractions.DeclareBlockersAction;
import forge.gamesimulationtests.util.playeractions.PlayerActions;
import forge.gamesimulationtests.util.playeractions.testactions.CardAssertAction;
import forge.gamesimulationtests.util.playeractions.testactions.EndTestAction;
import forge.gamesimulationtests.util.playeractions.testactions.PlayerAssertAction;

public class ComprehensiveRulesSection104 extends BaseGameSimulationTest {
	@Test
	public void test_104_2a_player_wins_if_all_opponents_left_even_if_he_couldnt_win() {
		/*Due to 1's Abyssal Persecutor, he can't win and 2 can't lose (despite the fact that he attempts to draw from an empty deck on turn 2)
		2 concedes during turn 3 (after 1 drew his last card), and 1 wins after all...*/
		GameWrapper gameWrapper = new GameWrapper( 
				new GameStateSpecificationBuilder()
						.addCard( new CardSpecificationBuilder( "Abyssal Persecutor" ).controller( PlayerSpecification.PLAYER_1 ).battlefield() )
						.addCard( new CardSpecificationBuilder( "Swamp" ).owner( PlayerSpecification.PLAYER_1 ).library() )
						.build(), 
				new PlayerActions(
						new EndTestAction( PlayerSpecification.PLAYER_2 ).when( new ActionPreCondition().turn( 3 ) )
				)
		);
		runGame( gameWrapper, PlayerSpecification.PLAYER_1, 3 );
	}

	@Test
	public void test_104_2b_effect_may_state_that_player_wins() {
		GameWrapper gameWrapper = new GameWrapper(
				new GameStateSpecificationBuilder()
						.addPlayerFact( PlayerSpecificationBuilder.player2().life( 1 ) )
						.addCard( new CardSpecificationBuilder( "Near-Death Experience" ).controller( PlayerSpecification.PLAYER_2 ).battlefield().build() )
						.build(),
				null
		);
		runGame( gameWrapper, PlayerSpecification.PLAYER_2, 2 );
	}

	@Test
	public void test_104_3b_player_with_zero_life_loses_the_game() {
		GameWrapper gameWrapper = new GameWrapper(
				new GameStateSpecificationBuilder()
						.addPlayerFact( PlayerSpecificationBuilder.player2().life( 0 ) )
						.build(),
				null
		);
		runGame( gameWrapper, PlayerSpecification.PLAYER_1, 1 );
	}

	@Test
	public void test_104_3b_player_with_less_than_zero_life_loses_the_game() {
		GameWrapper gameWrapper = new GameWrapper(
				new GameStateSpecificationBuilder()
						.addPlayerFact( PlayerSpecificationBuilder.player2().life( -1 ) )
						.build(),
				null
		);
		runGame( gameWrapper, PlayerSpecification.PLAYER_1, 1 );
	}

	@Test
	public void test_104_3b_player_with_less_than_zero_life_loses_the_game_only_when_a_player_receives_priority() {
		//The Lightning Helix targeting himself theoretically drops him to -1, but he's back up to 2 before he could lose
		GameWrapper gameWrapper = new GameWrapper(
				new GameStateSpecificationBuilder()
						.addPlayerFact( PlayerSpecificationBuilder.player1().life( 2 ) )
						.addCard( new CardSpecificationBuilder( "Lightning Helix" ).owner( PlayerSpecification.PLAYER_1 ).hand() )
						.build(),
				new PlayerActions(
						new CastSpellFromHandAction( PlayerSpecification.PLAYER_1, "Lightning Helix" ),
						new CardAssertAction( new CardSpecificationBuilder( "Lightning Helix" ).owner( PlayerSpecification.PLAYER_1 ).graveyard() )
								.when( new ActionPreCondition().turn( 1 ).phase( PhaseType.MAIN1 ) ),
						new PlayerAssertAction( PlayerSpecificationBuilder.player1().life( 2 ) ),
						new EndTestAction( PlayerSpecification.PLAYER_2 ).when( new ActionPreCondition().phase( PhaseType.MAIN2 ) )
				)
		);
		runGame( gameWrapper, PlayerSpecification.PLAYER_1, 1 );
	}

	@Test
	public void test_104_3b_player_with_less_than_zero_life_loses_the_game_only_when_a_player_receives_priority_variant_with_combat() {
		//Player 2 has 2 life, then takes 3 combat damage but also gains 2 life from lifelink
		//TODO: is it actually this rule that makes this situation work, or is combat damage handled simultaneously due to another rule?
		GameWrapper gameWrapper = new GameWrapper(
				new GameStateSpecificationBuilder()
						.addPlayerFact( PlayerSpecificationBuilder.player2().life( 2 ) )
						.addCard( new CardSpecificationBuilder( "Hill Giant" ).owner( PlayerSpecification.PLAYER_1 ).battlefield() )
						.addCard( new CardSpecificationBuilder( "Grizzly Bears" ).owner( PlayerSpecification.PLAYER_1 ).battlefield() )
						.addCard( new CardSpecificationBuilder( "Ajani's Sunstriker" ).owner( PlayerSpecification.PLAYER_2 ).battlefield() )
						.build(),
				new PlayerActions(
						new DeclareAttackersAction( PlayerSpecification.PLAYER_1 )
								.attack( new CardSpecificationBuilder( "Hill Giant" ).build() )
								.attack( new CardSpecificationBuilder( "Grizzly Bears" ).build() ),
						new DeclareBlockersAction( PlayerSpecification.PLAYER_2 )
								.block( new CardSpecificationBuilder( "Grizzly Bears" ).build(), new CardSpecificationBuilder( "Ajani's Sunstriker" ).build() ),
						new PlayerAssertAction( PlayerSpecificationBuilder.player2().life( 1 ) )
								.when( new ActionPreCondition().turn( 1 ).phase( PhaseType.COMBAT_END ) ),
						new EndTestAction( PlayerSpecification.PLAYER_1 ).when( new ActionPreCondition().phase( PhaseType.MAIN2 ) )
				)
		);
		runGame( gameWrapper, PlayerSpecification.PLAYER_2, 1 );
	}

	@Test
	public void test_104_3c_player_who_draws_card_with_empty_library_loses() {
		GameWrapper gameWrapper = new GameWrapper( null, null );
		runGame( gameWrapper, PlayerSpecification.PLAYER_1, 2 );
	}

	@Test
	public void test_104_3c_player_who_draws_more_cards_than_library_contains_draw_as_much_as_possible_and_loses() {
		GameWrapper gameWrapper = new GameWrapper( 
				new GameStateSpecificationBuilder()
						.addCard( new CardSpecificationBuilder( "Tidings" ).owner( PlayerSpecification.PLAYER_1 ).hand() )
						.addCard( new CardSpecificationBuilder( "Island" ).owner( PlayerSpecification.PLAYER_1 ).library() )
						.addCard( new CardSpecificationBuilder( "Mountain" ).owner( PlayerSpecification.PLAYER_1 ).library() )
						.build(), 
				new PlayerActions(
						new CastSpellFromHandAction( PlayerSpecification.PLAYER_1, "Tidings" )
				) 
		);
		runGame( gameWrapper, PlayerSpecification.PLAYER_2, 1,
				new CardAssertAction( new CardSpecificationBuilder( "Island" ).owner( PlayerSpecification.PLAYER_1 ).hand() ),
				new CardAssertAction( new CardSpecificationBuilder( "Mountain" ).owner( PlayerSpecification.PLAYER_1 ).hand() )
		);
	}

	@Test
	public void test_104_3d_player_with_ten_poison_counters_loses() {
		GameWrapper gameWrapper = new GameWrapper(
				new GameStateSpecificationBuilder()
						.addPlayerFact( PlayerSpecificationBuilder.player2().poison( 10 ) )
						.build(),
				null
		);
		runGame( gameWrapper, PlayerSpecification.PLAYER_1, 1 );
	}

	@Test
	public void test_104_3d_player_with_more_than_ten_poison_counters_loses() {
		GameWrapper gameWrapper = new GameWrapper(
				new GameStateSpecificationBuilder()
						.addPlayerFact( PlayerSpecificationBuilder.player2().poison( 11 ) )
						.build(),
				null
		);
		runGame( gameWrapper, PlayerSpecification.PLAYER_1, 1 );
	}

	@Test
	public void test_104_3e_effect_may_state_that_player_loses() {
		GameWrapper gameWrapper = new GameWrapper(
				new GameStateSpecificationBuilder()
						.addPlayerFact( PlayerSpecificationBuilder.player2().life( 1 ) )
						.addCard( new CardSpecificationBuilder( "Final Fortune" ).controller( PlayerSpecification.PLAYER_1 ).hand() )
						.addCard( new CardSpecificationBuilder( "Mountain" ).controller( PlayerSpecification.PLAYER_1 ).library() )
						.build(),
				new PlayerActions(
						new CastSpellFromHandAction( PlayerSpecification.PLAYER_1, "Final Fortune" ),
						new CardAssertAction( new CardSpecificationBuilder( "Mountain" ).owner( PlayerSpecification.PLAYER_1 ).hand() )
								.when( new ActionPreCondition().turn( 2 ).phase( PhaseType.MAIN2 ) )
				)
		);
		runGame( gameWrapper, PlayerSpecification.PLAYER_2, 2 );
	}

	@Test( description = "Historical fixture; NOT an isolated CR 104.3f simultaneous win/loss proof (see D24ExecutionGuardTest UNQUALIFIED_RULE_OBLIGATIONS)" )
	public void test_104_3f_if_a_player_would_win_and_lose_simultaneously_he_loses() {
        // Historical method name retained for evidence continuity. Under current
        // CR120.4b and121.7, Nefarious Lich's unpayable damage replacement ends
        // the game before replacement-caused draws. This scenario's required
        // loss is correct, but it does not isolate simultaneous win/loss104.3f.
        // LichDamageReplacementTest checks paid activation, the loss route and
        // the contrasting real Maniac win with an exilable graveyard card.
		GameWrapper gameWrapper = new GameWrapper(
				new GameStateSpecificationBuilder()
						.addCard( new CardSpecificationBuilder( "Swamp" ).controller( PlayerSpecification.PLAYER_1 ).battlefield() )
						.addCard( new CardSpecificationBuilder( "Laboratory Maniac" ).controller( PlayerSpecification.PLAYER_1 ).battlefield() )
						.addCard( new CardSpecificationBuilder( "Nefarious Lich" ).controller( PlayerSpecification.PLAYER_1 ).battlefield() )
						.addCard( new CardSpecificationBuilder( "Thrashing Wumpus" ).controller( PlayerSpecification.PLAYER_1 ).battlefield() )
						.addCard( new CardSpecificationBuilder( "Lifelink" ).controller( PlayerSpecification.PLAYER_1 ).battlefield().target( new CardSpecificationBuilder( "Thrashing Wumpus" ).build() ) )
						.build(),
				new PlayerActions(
						new ActivateAbilityAction( PlayerSpecification.PLAYER_1, new CardSpecificationBuilder( "Thrashing Wumpus" ).build() )
				)
		);
		runGame( gameWrapper, PlayerSpecification.PLAYER_2, 1 );
		// The activation was really paid with the declared Swamp, and the loss is
		// Nefarious Lich's own, not a draw or another route.
		Assert.assertTrue( CardSpecificationHandler.INSTANCE.find( gameWrapper.getGame(),
				new CardSpecificationBuilder( "Swamp" ).controller( PlayerSpecification.PLAYER_1 ).battlefield().build() ).isTapped(),
				"the activation must be paid with the declared Swamp" );
		forge.game.player.PlayerOutcome lich = forge.gamesimulationtests.util.player.PlayerSpecificationHandler.INSTANCE
				.find( gameWrapper.getGame(), PlayerSpecification.PLAYER_1 ).getOutcome();
		Assert.assertEquals( lich.lossState, forge.game.player.GameLossReason.SpellEffect );
		Assert.assertEquals( lich.loseConditionSpell, "Nefarious Lich" );
	}
}
